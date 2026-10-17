package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

type recOpts struct {
	limit   time.Duration
	size    int
	fps     int
	bitrate string
	touches bool
	show    bool
	gif     bool
	engine  string
	stamp   bool
}

func recFlags(e *env, name string) (*flag.FlagSet, *recOpts) {
	o := &recOpts{}
	fs := newFlags(e, name)
	fs.DurationVar(&o.limit, "time", 0, "stop after this long (e.g. 30s, 2m)")
	fs.IntVar(&o.size, "size", 0, "limit the longer side to N pixels (0 = native)")
	fs.IntVar(&o.fps, "fps", 30, "maximum frame rate (scrcpy engine)")
	fs.StringVar(&o.bitrate, "bitrate", "4M", "video bit rate")
	fs.BoolVar(&o.touches, "touches", false, "show touches on screen")
	fs.BoolVar(&o.show, "show", false, "also open the mirror window (scrcpy engine)")
	fs.BoolVar(&o.gif, "gif", false, "also make a GIF")
	fs.StringVar(&o.engine, "engine", "scrcpy", "scrcpy, or device (screenrecord: max 180 s, no scrcpy needed)")
	fs.BoolVar(&o.stamp, "stamp", false, "timestamp overlay (device engine)")
	return fs, o
}

func cmdRec(ctx context.Context, e *env, args []string) error {
	fs, o := recFlags(e, "rec")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	if len(pos) > 0 && pos[0] == "stop" {
		return e.recStop(ctx)
	}
	background := len(pos) > 0 && pos[0] == "start"
	if background {
		pos = pos[1:]
	}
	if o.engine != "scrcpy" && o.engine != "device" {
		return fmt.Errorf("--engine must be scrcpy or device, not %q", o.engine)
	}
	if o.engine == "device" && (background || o.show) {
		return errors.New("rec start and --show need the scrcpy engine")
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	pkg, _, _ := d.Focus(ctx)
	mp4, err := e.capturePath(ctx, time.Now(), pkg, strings.Join(pos, " "), ".mp4")
	if err != nil {
		return err
	}
	if o.engine == "device" {
		return e.recDevice(ctx, d, o, mp4)
	}
	if err := needScrcpy(); err != nil {
		return err
	}
	mkv := strings.TrimSuffix(mp4, ".mp4") + ".mkv"
	sargs := scrcpyArgs(d, o, mkv, o.show)
	if background {
		return e.recStart(ctx, d, sargs, mkv, mp4, o.gif)
	}
	if o.limit > 0 {
		fmt.Fprintf(e.stderr, "recording %s (stops after %s)…\n", display(mp4), o.limit)
	} else {
		fmt.Fprintf(e.stderr, "recording %s, Ctrl-C to stop…\n", display(mp4))
	}
	if err := e.runScrcpy(ctx, d, sargs, !o.show); err != nil {
		return err
	}
	return e.finishRecording(ctx, mkv, mp4, o.gif)
}

func needScrcpy() error {
	if _, err := exec.LookPath("scrcpy"); err != nil {
		return errors.New("scrcpy not found: brew install scrcpy (or use --engine device)")
	}
	return nil
}

// scrcpyArgs builds the scrcpy command line. With window false it records headless.
func scrcpyArgs(d *adb.Device, o *recOpts, mkv string, window bool) []string {
	a := []string{"--serial=" + d.Serial, "--no-audio"}
	if !window {
		a = append(a, "--no-window", "--no-playback")
	}
	if o.fps > 0 {
		a = append(a, "--max-fps="+strconv.Itoa(o.fps))
	}
	if o.bitrate != "" {
		a = append(a, "--video-bit-rate="+o.bitrate)
	}
	if o.size > 0 {
		a = append(a, "--max-size="+strconv.Itoa(o.size))
	}
	if o.limit > 0 {
		a = append(a, "--time-limit="+strconv.Itoa(int(math.Ceil(o.limit.Seconds()))))
	}
	if o.touches {
		a = append(a, "--show-touches")
	}
	if mkv != "" {
		a = append(a, "--record="+mkv)
	}
	return a
}

// scrcpy 5.0.1 on macOS sometimes crashed at startup (segmentation fault, 2 of
// about 40 runs against the Veidoo F10), so a run that dies this early is retried once.
const scrcpyStartupGrace = 4 * time.Second

// runScrcpy runs scrcpy in its own process group and forwards Ctrl-C to it as
// SIGINT, so it can finish the file. quiet hides its INFO lines unless -v.
func (e *env) runScrcpy(ctx context.Context, d *adb.Device, args []string, quiet bool) error {
	for attempt := 1; ; attempt++ {
		start := time.Now()
		err := e.runScrcpyOnce(ctx, d, args, quiet)
		if err == nil || ctx.Err() != nil || attempt == 2 || time.Since(start) > scrcpyStartupGrace {
			return err
		}
		first, _, _ := strings.Cut(err.Error(), "\n")
		fmt.Fprintf(e.stderr, "%s at startup, retrying once…\n", first)
	}
}

func (e *env) runScrcpyOnce(ctx context.Context, d *adb.Device, args []string, quiet bool) error {
	cmd := exec.Command("scrcpy", args...)
	cmd.Env = append(os.Environ(), "ADB="+d.Bin)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	var logbuf bytes.Buffer
	if quiet && !e.verbose {
		cmd.Stdout, cmd.Stderr = &logbuf, &logbuf
	} else {
		cmd.Stdout, cmd.Stderr = e.stderr, e.stderr
	}
	e.logf("$ ADB=%s scrcpy %s", d.Bin, strings.Join(args, " "))
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("scrcpy: %w", err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			return fmt.Errorf("scrcpy: %v\n%s", err, tail(logbuf.String(), 12))
		}
		return nil
	case <-ctx.Done():
		_ = cmd.Process.Signal(os.Interrupt)
		select {
		case <-done:
		case <-time.After(10 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
		return nil
	}
}

func tail(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// finishRecording turns scrcpy's .mkv (readable even if scrcpy was killed or
// the device rebooted) into an .mp4 that QuickTime can open, losslessly.
func (e *env) finishRecording(ctx context.Context, mkv, mp4 string, gif bool) error {
	cctx, cancel := cleanupCtx(ctx)
	defer cancel()
	if st, err := os.Stat(mkv); err != nil {
		return errors.New("no recording was written (run with -v to see scrcpy's output)")
	} else if st.Size() == 0 {
		os.Remove(mkv)
		return errors.New("stopped before scrcpy recorded its first frame: nothing saved")
	}
	final := mkv
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		fmt.Fprintln(e.stderr, "ffmpeg not found (brew install ffmpeg): keeping the .mkv")
	} else {
		out, err := exec.CommandContext(cctx, "ffmpeg", "-v", "error", "-y", "-i", mkv, "-c", "copy", "-movflags", "+faststart", mp4).CombinedOutput()
		if err != nil {
			return fmt.Errorf("remux to mp4 failed, the recording is in %s: %v: %s", mkv, err, out)
		}
		os.Remove(mkv)
		final = mp4
	}
	return e.reportVideo(cctx, final, gif)
}

func (e *env) reportVideo(ctx context.Context, path string, gif bool) error {
	e.printPath(path, probe(ctx, path))
	if gif {
		g, err := makeGIF(ctx, path, 640, 12)
		if err != nil {
			return err
		}
		e.printPath(g, "")
	}
	return nil
}

// probe describes a video: duration, size, frame rate, file size. Packets are
// counted, not decoded, so it stays fast on long recordings.
func probe(ctx context.Context, path string) string {
	st, err := os.Stat(path)
	if err != nil {
		return "missing"
	}
	out, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets",
		"-show_entries", "stream=width,height,nb_read_packets:format=duration", "-of", "default=noprint_wrappers=1", path).Output()
	if err != nil {
		return humanBytes(st.Size())
	}
	v := map[string]string{}
	for _, line := range strings.Split(string(out), "\n") {
		if k, val, ok := strings.Cut(strings.TrimSpace(line), "="); ok {
			v[k] = val
		}
	}
	dur, _ := strconv.ParseFloat(v["duration"], 64)
	frames, _ := strconv.Atoi(v["nb_read_packets"])
	s := fmt.Sprintf("%.1f s, %sx%s", dur, v["width"], v["height"])
	switch fps := float64(frames) / dur; {
	case frames <= 1 || dur <= 0:
		s += ", 1 frame (nothing moved on screen)"
	case fps < 10:
		s += fmt.Sprintf(", %.1f fps", fps)
	default:
		s += fmt.Sprintf(", %.0f fps", fps)
	}
	return s + ", " + humanBytes(st.Size())
}

// --- device engine: screenrecord on the device ---------------------------------

var physSizeRE = regexp.MustCompile(`(\d+)x(\d+)`)

// fitSize scales w×h so the longer side is at most max, keeping even numbers.
func fitSize(w, h, max int) (int, int) {
	if max <= 0 || (w <= max && h <= max) {
		return w, h
	}
	long := w
	if h > long {
		long = h
	}
	w, h = w*max/long, h*max/long
	return w &^ 1, h &^ 1
}

func (e *env) recDevice(ctx context.Context, d *adb.Device, o *recOpts, mp4 string) error {
	limit := int(math.Ceil(o.limit.Seconds()))
	if limit <= 0 || limit > 180 {
		if limit > 180 {
			fmt.Fprintln(e.stderr, "screenrecord stops at 180 s: use the scrcpy engine for longer recordings")
		}
		limit = 180
	}
	script := "screenrecord --bit-rate " + adb.Quote(o.bitrate) + " --time-limit " + strconv.Itoa(limit)
	if o.size > 0 {
		out, err := d.MustShell(ctx, "wm size")
		m := physSizeRE.FindStringSubmatch(out)
		if err != nil || m == nil {
			return fmt.Errorf("wm size: %v %q", err, out)
		}
		w, _ := strconv.Atoi(m[1])
		h, _ := strconv.Atoi(m[2])
		w, h = fitSize(w, h, o.size)
		script += fmt.Sprintf(" --size %dx%d", w, h)
	}
	if o.stamp {
		script += " --bugreport"
	}
	remote := fmt.Sprintf("/data/local/tmp/fr-rec-%d.mp4", time.Now().Unix())
	script += " " + remote

	if o.touches {
		restore, err := setShowTouches(ctx, d)
		if err != nil {
			return err
		}
		defer restore()
	}

	fmt.Fprintf(e.stderr, "recording %s on the device (max %d s), Ctrl-C to stop…\n", display(mp4), limit)
	cmd := d.Command(context.WithoutCancel(ctx), "shell", script)
	var logbuf bytes.Buffer
	cmd.Stdout, cmd.Stderr = &logbuf, &logbuf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case <-done:
	case <-ctx.Done():
		// Ctrl-C on the Mac does not reach the device without the shell
		// protocol: stop screenrecord there so it writes the MP4 index.
		stopCtx, stop := cleanupCtx(ctx)
		_, _, _ = d.Shell(stopCtx, "pkill -INT screenrecord")
		stop()
		select {
		case <-done:
		case <-time.After(8 * time.Second):
			_ = cmd.Process.Kill()
		}
	}
	cctx, cancel := cleanupCtx(ctx) // after the recording: its timeout covers only the cleanup
	defer cancel()
	for i := 0; i < 25; i++ { // wait until screenrecord has really exited
		if out, _, err := d.Shell(cctx, "ps screenrecord"); err != nil || !strings.Contains(out, "screenrecord") {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	defer d.Shell(cctx, "rm -f "+remote)
	if _, err := d.Run(cctx, "pull", remote, mp4); err != nil {
		return fmt.Errorf("screenrecord produced no file: %s\n%v", strings.TrimSpace(logbuf.String()), err)
	}
	return e.reportVideo(cctx, mp4, o.gif)
}

// setShowTouches turns on the touch dots and returns a function that restores
// the exact previous value ("null" means it was never set).
func setShowTouches(ctx context.Context, d *adb.Device) (func(), error) {
	prev, err := d.MustShell(ctx, "settings get system show_touches")
	if err != nil {
		return nil, err
	}
	prev = strings.TrimSpace(prev)
	if _, err := d.MustShell(ctx, "settings put system show_touches 1"); err != nil {
		return nil, err
	}
	return func() {
		cctx, cancel := cleanupCtx(ctx)
		defer cancel()
		if prev == "null" || prev == "" {
			_, _, _ = d.Shell(cctx, "settings delete system show_touches")
		} else {
			_, _, _ = d.Shell(cctx, "settings put system show_touches "+adb.Quote(prev))
		}
	}, nil
}

// --- background recording -------------------------------------------------------

type recState struct {
	PID     int       `json:"pid"`
	MKV     string    `json:"mkv"`
	MP4     string    `json:"mp4"`
	GIF     bool      `json:"gif"`
	Started time.Time `json:"started"`
}

func (e *env) recFiles(ctx context.Context) (state, log string) {
	root := e.capturesRoot(ctx)
	return filepath.Join(root, ".rec.pid"), filepath.Join(root, ".rec.log")
}

func alive(pid int) bool {
	if pid <= 0 || syscall.Kill(pid, 0) != nil {
		return false
	}
	out, err := exec.Command("ps", "-p", strconv.Itoa(pid), "-o", "comm=").Output()
	return err == nil && strings.Contains(string(out), "scrcpy")
}

func (e *env) recStart(ctx context.Context, d *adb.Device, args []string, mkv, mp4 string, gif bool) error {
	statePath, logPath := e.recFiles(ctx)
	if b, err := os.ReadFile(statePath); err == nil {
		var s recState
		if json.Unmarshal(b, &s) == nil && alive(s.PID) {
			return fmt.Errorf("already recording %s: run fr rec stop first", display(s.MP4))
		}
	}
	if err := os.MkdirAll(filepath.Dir(statePath), 0o755); err != nil {
		return err
	}
	e.logf("$ ADB=%s scrcpy %s &", d.Bin, strings.Join(args, " "))
	var cmd *exec.Cmd
	for attempt := 1; ; attempt++ {
		var err error
		cmd, err = spawnScrcpy(d, args, logPath)
		if err == nil {
			break
		}
		if attempt == 2 {
			return err
		}
		first, _, _ := strings.Cut(err.Error(), "\n")
		fmt.Fprintf(e.stderr, "%s at startup, retrying once…\n", first)
	}
	s := recState{PID: cmd.Process.Pid, MKV: mkv, MP4: mp4, GIF: gif, Started: time.Now()}
	b, _ := json.Marshal(s)
	if err := os.WriteFile(statePath, b, 0o644); err != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		return err
	}
	fmt.Fprintf(e.stdout, "recording %s in the background: fr rec stop\n", display(mp4))
	return nil
}

// spawnScrcpy starts scrcpy detached from this terminal, logging to logPath, and
// returns once it has started recording (or after a few seconds).
func spawnScrcpy(d *adb.Device, args []string, logPath string) (*exec.Cmd, error) {
	logf, err := os.Create(logPath)
	if err != nil {
		return nil, err
	}
	defer logf.Close()
	cmd := exec.Command("scrcpy", args...)
	cmd.Env = append(os.Environ(), "ADB="+d.Bin)
	cmd.Stdout, cmd.Stderr = logf, logf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true} // survives this terminal
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("scrcpy: %w", err)
	}
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	deadline := time.After(scrcpyStartupGrace)
	for {
		select {
		case err := <-exited:
			b, _ := os.ReadFile(logPath)
			return nil, fmt.Errorf("scrcpy exited: %v\n%s", err, tail(string(b), 12))
		case <-deadline:
			return cmd, nil
		case <-time.After(100 * time.Millisecond):
			if b, _ := os.ReadFile(logPath); bytes.Contains(b, []byte("Recording started")) {
				return cmd, nil
			}
		}
	}
}

func (e *env) recStop(ctx context.Context) error {
	statePath, logPath := e.recFiles(ctx)
	b, err := os.ReadFile(statePath)
	if err != nil {
		return errors.New("no background recording (fr rec start)")
	}
	var s recState
	if err := json.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("%s: %w", statePath, err)
	}
	if alive(s.PID) {
		_ = syscall.Kill(s.PID, syscall.SIGINT)
		for i := 0; i < 100 && alive(s.PID); i++ {
			time.Sleep(100 * time.Millisecond)
		}
		if alive(s.PID) {
			_ = syscall.Kill(s.PID, syscall.SIGKILL)
		}
	}
	os.Remove(statePath)
	if err := e.finishRecording(ctx, s.MKV, s.MP4, s.GIF); err != nil {
		lb, _ := os.ReadFile(logPath)
		return fmt.Errorf("%w\n%s", err, tail(string(lb), 12))
	}
	fmt.Fprintf(e.stderr, "recorded for %s\n", time.Since(s.Started).Round(100*time.Millisecond))
	return nil
}

// --- gif -------------------------------------------------------------------------

func cmdGif(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "gif")
	width := fs.Int("width", 640, "width in pixels")
	fps := fs.Int("fps", 12, "frames per second")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	if len(pos) != 1 {
		fs.Usage()
		return flag.ErrHelp
	}
	g, err := makeGIF(ctx, pos[0], *width, *fps)
	if err != nil {
		return err
	}
	e.printPath(g, "")
	return nil
}

// makeGIF writes <video>.gif with a palette computed from the video.
func makeGIF(ctx context.Context, video string, width, fps int) (string, error) {
	if _, err := os.Stat(video); err != nil {
		return "", err
	}
	out := strings.TrimSuffix(video, filepath.Ext(video)) + ".gif"
	filter := fmt.Sprintf("fps=%d,scale=%d:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5", fps, width)
	cmd := exec.CommandContext(ctx, "ffmpeg", "-v", "error", "-y", "-i", video, "-vf", filter, out)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("ffmpeg gif: %v: %s", err, strings.TrimSpace(stderr.String()))
	}
	return out, nil
}
