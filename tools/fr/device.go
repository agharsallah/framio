package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

// infoScript prints key=value lines (and meminfo lines) in one adb round trip.
const infoScript = `echo "model=$(getprop ro.product.model)"
echo "manufacturer=$(getprop ro.product.manufacturer)"
echo "android=$(getprop ro.build.version.release)"
echo "sdk=$(getprop ro.build.version.sdk)"
echo "abi=$(getprop ro.product.cpu.abi)"
echo "platform=$(getprop ro.board.platform)"
echo "size=$(wm size | tail -1)"
echo "density=$(wm density | tail -1)"
echo "ip=$(ip -f inet addr show wlan0 2>/dev/null | grep inet)"
echo "uptime=$(cat /proc/uptime)"
echo "su=$(command -v su 2>/dev/null || which su 2>/dev/null)"
grep -E '^(MemTotal|MemFree|MemAvailable|Cached):' /proc/meminfo
dumpsys window windows | grep mCurrentFocus`

var (
	ipRE  = regexp.MustCompile(`inet (\d+\.\d+\.\d+\.\d+)`)
	numRE = regexp.MustCompile(`\d+`)
)

type deviceInfo map[string]string

func parseInfo(out string) deviceInfo {
	m := deviceInfo{}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if k, v, ok := strings.Cut(line, "="); ok && !strings.Contains(k, " ") {
			m[k] = strings.TrimSpace(v)
		} else if k, v, ok := strings.Cut(line, ":"); ok && (strings.HasPrefix(k, "Mem") || k == "Cached") {
			m[k] = numRE.FindString(v)
		}
	}
	pkg, act := adb.ParseFocus(out)
	m["focus"] = pkg
	if act != "" {
		m["focus"] = pkg + "/" + strings.TrimPrefix(act, pkg)
	}
	return m
}

func (i deviceInfo) kb(k string) int {
	n, _ := strconv.Atoi(i[k])
	return n
}

func (i deviceInfo) format(serial string) string {
	var b strings.Builder
	row := func(k, v string) { fmt.Fprintf(&b, "%-9s %s\n", k, v) }
	row("device", strings.TrimSpace(i["manufacturer"]+" "+i["model"])+" · "+serial)
	row("android", fmt.Sprintf("%s (API %s) · %s · %s", i["android"], i["sdk"], i["abi"], i["platform"]))
	_, size, _ := strings.Cut(i["size"], ": ")
	_, dpi, _ := strings.Cut(i["density"], ": ")
	row("screen", size+" @ "+dpi+" dpi")
	if m := ipRE.FindStringSubmatch(i["ip"]); m != nil {
		row("wifi ip", m[1])
	} else {
		row("wifi ip", "none")
	}
	row("front", i["focus"])
	avail := i.kb("MemAvailable")
	if avail == 0 {
		avail = i.kb("MemFree") + i.kb("Cached")
	}
	row("ram", fmt.Sprintf("%d MB available of %d MB", avail/1024, i.kb("MemTotal")/1024))
	if f := strings.Fields(i["uptime"]); len(f) > 0 {
		secs, _ := strconv.ParseFloat(f[0], 64)
		row("uptime", (time.Duration(secs) * time.Second).String())
	}
	if i["su"] != "" {
		row("root", "su at "+i["su"])
	} else {
		row("root", "no su")
	}
	return b.String()
}

func gatherInfo(ctx context.Context, d *adb.Device) (deviceInfo, error) {
	out, _, err := d.Shell(ctx, infoScript)
	if err != nil {
		return nil, err
	}
	return parseInfo(out), nil
}

func cmdInfo(ctx context.Context, e *env, args []string) error {
	if _, err := parseArgs(newFlags(e, "info"), args); err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	i, err := gatherInfo(ctx, d)
	if err != nil {
		return err
	}
	fmt.Fprint(e.stdout, i.format(d.Serial))
	return nil
}

// --- doctor -----------------------------------------------------------------------

var (
	adbVersionRE    = regexp.MustCompile(`Version (\S+)`)
	scrcpyVersionRE = regexp.MustCompile(`scrcpy ((\d+)\.(\d+)\S*)`)
	encoderMaxRE    = regexp.MustCompile(`max="(\d+x\d+)"`)
)

func adbVersion(ctx context.Context, bin string) string {
	out, _ := exec.CommandContext(ctx, bin, "version").Output()
	if m := adbVersionRE.FindStringSubmatch(string(out)); m != nil {
		return m[1]
	}
	return "?"
}

// otherADBs lists adb binaries other than the chosen one, with their versions.
func otherADBs(ctx context.Context, chosen string) []string {
	seen := map[string]bool{}
	if p, err := filepath.EvalSymlinks(chosen); err == nil {
		seen[p] = true
	}
	var cands []string
	for _, dir := range filepath.SplitList(os.Getenv("PATH")) {
		cands = append(cands, filepath.Join(dir, "adb"))
	}
	if sdk := sdkDir(""); sdk != "" {
		cands = append(cands, filepath.Join(sdk, "platform-tools", "adb"))
	}
	var out []string
	for _, c := range cands {
		p, err := filepath.EvalSymlinks(c)
		if err != nil || seen[p] {
			continue
		}
		seen[p] = true
		out = append(out, c+" ("+adbVersion(ctx, c)+")")
	}
	return out
}

func cmdDoctor(ctx context.Context, e *env, args []string) error {
	if _, err := parseArgs(newFlags(e, "doctor"), args); err != nil {
		return err
	}
	failed := 0
	check := func(ok bool, name, detail, fix string) {
		mark := "✓"
		if !ok {
			mark = "✗"
			failed++
		}
		fmt.Fprintf(e.stdout, "%s %-10s %s\n", mark, name, detail)
		if !ok && fix != "" {
			fmt.Fprintf(e.stdout, "  %-10s → %s\n", "", fix)
		}
	}
	warn := func(name, detail string) { fmt.Fprintf(e.stdout, "! %-10s %s\n", name, detail) }

	fmt.Fprintln(e.stdout, "Mac")
	bin, err := adb.FindBin()
	check(err == nil, "adb", bin+" "+adbVersion(ctx, bin), "install platform-tools or set $ADB")
	if err == nil {
		mine := adbVersion(ctx, bin)
		for _, o := range otherADBs(ctx, bin) {
			if !strings.HasSuffix(o, "("+mine+")") {
				warn("adb", "another adb, different version: "+o+": they restart each other's server; fr and scrcpy use "+bin)
			}
		}
	}
	if out, err := exec.CommandContext(ctx, "scrcpy", "--version").Output(); err != nil {
		check(false, "scrcpy", "not found", "brew install scrcpy")
	} else if m := scrcpyVersionRE.FindStringSubmatch(string(out)); m != nil {
		major, _ := strconv.Atoi(m[2])
		minor, _ := strconv.Atoi(m[3])
		check(major > 4 || major == 4 && minor >= 1, "scrcpy", m[1], "brew upgrade scrcpy (4.1+ adapts to encoder size limits)")
	}
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		p, err := exec.LookPath(tool)
		check(err == nil, tool, p, "brew install ffmpeg")
	}
	root, _ := gradleRoot(".")
	a, err := aapt2Path(root)
	check(err == nil, "aapt2", a, "sdkmanager \"build-tools;37.0.0\" (needed by fr run/install --launch)")
	jh, err := javaHome(ctx)
	check(err == nil, "java", jh, "install JDK 21 (Gradle needs 17+)")
	capRoot := e.capturesRoot(ctx)
	werr := os.MkdirAll(capRoot, 0o755)
	if werr == nil {
		var f *os.File
		if f, werr = os.CreateTemp(capRoot, ".fr-doctor-*"); werr == nil {
			f.Close()
			os.Remove(f.Name())
		}
	}
	check(werr == nil, "captures", display(capRoot), fmt.Sprint(werr))
	if exec.CommandContext(ctx, "git", "-C", capRoot, "rev-parse").Run() == nil {
		ignored := exec.CommandContext(ctx, "git", "-C", capRoot, "check-ignore", "-q", filepath.Join(capRoot, "x.png")).Run() == nil
		check(ignored, "gitignore", "captures are git-ignored", "add captures/ to .gitignore: screens can show private photos")
	}

	fmt.Fprintln(e.stdout, "\nDevice")
	d, err := e.device(ctx)
	if err != nil {
		check(false, "device", err.Error(), "")
		return exitError(1)
	}
	i, err := gatherInfo(ctx, d)
	if err != nil {
		check(false, "shell", err.Error(), "replug the cable")
		return exitError(1)
	}
	check(true, "device", strings.TrimSpace(i["manufacturer"]+" "+i["model"])+" · "+d.Serial, "")
	sdk, _ := strconv.Atoi(i["sdk"])
	check(sdk >= 21, "android", i["android"]+" (API "+i["sdk"]+")", "scrcpy needs Android 5+")
	feats, _ := d.Features(ctx)
	if strings.Contains(strings.Join(feats, ","), "shell_v2") {
		check(true, "shell", "shell protocol v2", "")
	} else {
		warn("shell", "no shell protocol: exit codes come from fr's marker, stderr is mixed into stdout")
	}
	if i["su"] != "" {
		check(true, "root", "su at "+i["su"]+" (used only by fr screen and fr sh --root)", "")
	} else {
		warn("root", "no su: fr screen falls back to wake/sleep keys")
	}
	if out, _, err := d.Shell(ctx, "grep -A3 'video_encoder.avc' /system/etc/media_codecs.xml /vendor/etc/media_codecs.xml 2>/dev/null | grep max= | head -1"); err == nil {
		if m := encoderMaxRE.FindStringSubmatch(out); m != nil {
			_, size, _ := strings.Cut(i["size"], ": ")
			warn("encoder", "H.264 encoder declares max "+m[1]+", screen is "+size+" (scrcpy shrinks the size itself if it has to; or fr rec --size 1152)")
		}
	}
	if out, _, err := d.Shell(ctx, "df /data"); err == nil {
		if free := dfFree(out); free != "" {
			check(true, "storage", "/data free: "+free, "")
		}
	}
	if failed > 0 {
		return exitError(1)
	}
	return nil
}

// dfFree reads the free column of `df /data`: "26.4G" on Android 6, KB on toybox df.
func dfFree(out string) string {
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) >= 4 && f[0] != "Filesystem" {
			if _, err := strconv.Atoi(f[3]); err == nil {
				return f[3] + " KB"
			}
			return f[3]
		}
	}
	return ""
}

// --- bug ----------------------------------------------------------------------------

func cmdBug(ctx context.Context, e *env, args []string) error {
	pos, err := parseArgs(newFlags(e, "bug"), args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	start := time.Now()
	dir, err := e.capturePath(ctx, start, "bug", strings.Join(pos, " "), "")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	files := map[string]string{
		"logcat.txt":     "logcat -d -v threadtime -b all -t 2000",
		"crash.txt":      "logcat -d -v threadtime -b crash",
		"activities.txt": "dumpsys activity activities",
		"windows.txt":    "dumpsys window windows",
		"meminfo.txt":    "dumpsys meminfo",
		"top.txt":        "top -n 1 -m 15",
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	var problems []string
	fail := func(name string, err error) {
		mu.Lock()
		defer mu.Unlock()
		problems = append(problems, name+": "+err.Error())
	}
	for name, script := range files {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out, _, err := d.Shell(ctx, script)
			if err == nil {
				err = os.WriteFile(filepath.Join(dir, name), []byte(out), 0o644)
			}
			if err != nil {
				fail(name, err)
			}
		}()
	}
	wg.Add(2)
	go func() {
		defer wg.Done()
		data, err := capturePNG(ctx, d, false)
		if err == nil {
			err = os.WriteFile(filepath.Join(dir, "screen.png"), data, 0o644)
		}
		if err != nil {
			fail("screen.png", err)
		}
	}()
	go func() {
		defer wg.Done()
		i, err := gatherInfo(ctx, d)
		if err == nil {
			err = os.WriteFile(filepath.Join(dir, "info.txt"), []byte(i.format(d.Serial)+"captured "+start.Format(time.RFC3339)+"\n"), 0o644)
		}
		if err != nil {
			fail("info.txt", err)
		}
	}()
	wg.Wait()
	if len(problems) > 0 {
		_ = os.WriteFile(filepath.Join(dir, "errors.txt"), []byte(strings.Join(problems, "\n")+"\n"), 0o644)
		fmt.Fprintf(e.stderr, "%d parts failed, see errors.txt\n", len(problems))
	}
	e.printPath(dir+"/", ms(time.Since(start)))
	return nil
}

// --- wait, reboot -------------------------------------------------------------------

// waitBoot waits for the device to come back and finish booting.
func (e *env) waitBoot(ctx context.Context, serial string) error {
	bin, err := adb.FindBin()
	if err != nil {
		return err
	}
	d := &adb.Device{Bin: bin, Serial: serial, Verbose: e.verbose, Log: e.stderr}
	start := time.Now()
	if _, err := d.Run(ctx, "wait-for-device"); err != nil {
		return err
	}
	for {
		if v, err := d.Prop(ctx, "sys.boot_completed"); err == nil && v == "1" {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	fmt.Fprintf(e.stdout, "booted (%s)\n", time.Since(start).Round(time.Second))
	return nil
}

func cmdWait(ctx context.Context, e *env, args []string) error {
	if _, err := parseArgs(newFlags(e, "wait"), args); err != nil {
		return err
	}
	serial := e.serial
	if serial == "" {
		serial = os.Getenv("ANDROID_SERIAL")
	}
	return e.waitBoot(ctx, serial)
}

func cmdReboot(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "reboot")
	wait := fs.Bool("wait", false, "wait until it has booted again")
	if _, err := parseArgs(fs, args); err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	if _, err := d.Run(ctx, "reboot"); err != nil {
		return err
	}
	if !*wait {
		return nil
	}
	time.Sleep(3 * time.Second) // let it drop off USB first
	return e.waitBoot(ctx, d.Serial)
}

// --- screen ---------------------------------------------------------------------------

const backlightDir = `b=$(ls -d /sys/class/backlight/* | head -1)`

func cmdScreen(ctx context.Context, e *env, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: fr screen on|off|<0-255>")
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	su, _, _ := d.Shell(ctx, "command -v su 2>/dev/null || which su 2>/dev/null")
	root := strings.TrimSpace(su) != ""
	level, isLevel := strconv.Atoi(args[0])
	switch {
	case isLevel == nil && (level < 0 || level > 255):
		return errors.New("brightness must be 0-255")
	case !root && args[0] == "on":
		return e.input(ctx, "input keyevent KEYCODE_WAKEUP")
	case !root && args[0] == "off":
		return e.input(ctx, "input keyevent KEYCODE_SLEEP")
	case !root:
		_, err := d.MustShell(ctx, "settings put system screen_brightness "+strconv.Itoa(level))
		return err
	}
	// Root: write the backlight directly; Android keeps running as if the screen were on.
	var script string
	switch {
	case args[0] == "off":
		script = backlightDir + `; v=$(cat $b/brightness); [ "$v" != 0 ] && echo $v > /data/local/tmp/fr-backlight; echo 0 > $b/brightness`
	case args[0] == "on":
		script = backlightDir + `; v=$(cat /data/local/tmp/fr-backlight 2>/dev/null); [ -z "$v" ] && v=$(( $(cat $b/max_brightness) * 3 / 4 )); echo $v > $b/brightness`
	case isLevel == nil:
		script = backlightDir + "; echo " + strconv.Itoa(level) + " > $b/brightness"
	default:
		return errors.New("usage: fr screen on|off|<0-255>")
	}
	out, rc, err := d.Root(ctx, script)
	if err != nil {
		return err
	}
	if rc != 0 {
		return fmt.Errorf("backlight: exit %d: %s", rc, strings.TrimSpace(out))
	}
	return nil
}

// --- sh -------------------------------------------------------------------------------

func cmdSh(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "sh")
	root := fs.Bool("root", false, "run as root (su)")
	if err := fs.Parse(args); err != nil { // flags only before the command
		return err
	}
	if fs.NArg() == 0 {
		fs.Usage()
		return flag.ErrHelp
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	script := strings.Join(fs.Args(), " ")
	var out string
	var rc int
	if *root {
		out, rc, err = d.Root(ctx, script)
	} else {
		out, rc, err = d.Shell(ctx, script)
	}
	if err != nil {
		return err
	}
	fmt.Fprint(e.stdout, out)
	if rc != 0 {
		return exitError(rc)
	}
	return nil
}

func cmdVersion(ctx context.Context, e *env, args []string) error {
	v := "fr (devel)"
	if bi, ok := debug.ReadBuildInfo(); ok {
		var rev, when, dirty string
		for _, s := range bi.Settings {
			switch s.Key {
			case "vcs.revision":
				rev = s.Value
			case "vcs.time":
				when = s.Value
			case "vcs.modified":
				if s.Value == "true" {
					dirty = "+dirty"
				}
			}
		}
		if len(rev) > 12 {
			rev = rev[:12]
		}
		if rev != "" {
			v = fmt.Sprintf("fr %s%s (%s)", rev, dirty, when)
		}
		v += " " + bi.GoVersion
	}
	fmt.Fprintln(e.stdout, v)
	return nil
}
