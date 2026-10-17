package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"image/png"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

// --- command tests against testdata/fake-adb -------------------------------------

type fakeRun struct {
	out, errOut bytes.Buffer
	log         string // every adb call, one per line
	captures    string
}

// runFake runs fr in-process with adb replaced by testdata/fake-adb.
func runFake(t *testing.T, args ...string) (*fakeRun, error) {
	t.Helper()
	dir := t.TempDir()
	fake, _ := filepath.Abs("testdata/fake-adb")
	logPath := filepath.Join(dir, "adb.log")
	t.Setenv("ADB", fake)
	t.Setenv("FAKE_ADB_LOG", logPath)
	t.Setenv("FAKE_SCREEN", fakeScreen(t, dir))
	t.Setenv("ANDROID_SERIAL", "")
	t.Setenv("FR_LOCK", "")
	r := &fakeRun{captures: filepath.Join(dir, "captures")}
	e := &env{stdout: &r.out, stderr: &r.errOut, out: r.captures}
	err := run(context.Background(), e, args)
	b, _ := os.ReadFile(logPath)
	r.log = string(b)
	return r, err
}

// fakeScreen writes what `screencap | gzip -1` sends: a 4×2 RGBA image.
func fakeScreen(t *testing.T, dir string) string {
	raw := make([]byte, 12, 12+4*2*4)
	binary.LittleEndian.PutUint32(raw[0:], 4)
	binary.LittleEndian.PutUint32(raw[4:], 2)
	binary.LittleEndian.PutUint32(raw[8:], 1)
	for i := 0; i < 8; i++ {
		raw = append(raw, 200, 30, 40, 255)
	}
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	zw.Write(raw)
	zw.Close()
	p := filepath.Join(dir, "screen.gz")
	if err := os.WriteFile(p, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestShotCommand(t *testing.T) {
	r, err := runFake(t, "shot", "login screen")
	if err != nil {
		t.Fatalf("%v\n%s", err, r.errOut.String())
	}
	matches, _ := filepath.Glob(filepath.Join(r.captures, "*", "*_dev.framio.mawaqit_login-screen.png"))
	if len(matches) != 1 {
		t.Fatalf("captures: %v, stdout %q", matches, r.out.String())
	}
	f, _ := os.Open(matches[0])
	defer f.Close()
	img, err := png.Decode(f)
	if err != nil || img.Bounds().Dx() != 4 || img.Bounds().Dy() != 2 {
		t.Fatalf("png: %v %v", err, img)
	}
	if red, _, _, _ := img.At(0, 0).RGBA(); red>>8 != 200 {
		t.Errorf("pixel red = %d", red>>8)
	}
	if !strings.Contains(r.out.String(), filepath.Base(matches[0])) {
		t.Errorf("path not printed: %q", r.out.String())
	}
}

func TestTapByText(t *testing.T) {
	r, err := runFake(t, "tap", "next", "prayer")
	if err != nil {
		t.Fatalf("%v\n%s", err, r.errOut.String())
	}
	if !strings.Contains(r.log, "input tap 855 156") {
		t.Errorf("tap center of NEXT PRAYER [790,147][920,165]; adb calls:\n%s", r.log)
	}
	// Two exact "Fajr" elements: ambiguous without --nth.
	r, err = runFake(t, "tap", "fajr")
	if err == nil || !strings.Contains(err.Error(), "--nth") || strings.Contains(r.log, "input tap") {
		t.Errorf("want ambiguity error, got %v\n%s", err, r.log)
	}
	r, err = runFake(t, "tap", "fajr", "--nth", "2")
	if err != nil || !strings.Contains(r.log, "input tap 166 524") {
		t.Errorf("--nth 2: %v\n%s", err, r.log)
	}
	if _, err = runFake(t, "tap", "not on screen"); err == nil {
		t.Error("unknown text must fail")
	}
	r, err = runFake(t, "tap", "10", "20")
	if err != nil || !strings.Contains(r.log, "input tap 10 20") || strings.Contains(r.log, "uiautomator") {
		t.Errorf("coordinates: %v\n%s", err, r.log)
	}
}

func TestShExitCode(t *testing.T) {
	r, err := runFake(t, "sh", "echo hi; exit 3")
	var code exitError
	if !errors.As(err, &code) || code != 3 || r.out.String() != "hi\n" {
		t.Errorf("got %v %q", err, r.out.String())
	}
	if _, err := runFake(t, "sh", "true"); err != nil {
		t.Errorf("true: %v", err)
	}
}

func TestLogsCommand(t *testing.T) {
	r, err := runFake(t, "logs", "dev.framio.mawaqit", "--dump", "--raw")
	if err != nil {
		t.Fatal(err)
	}
	out := r.out.String()
	for _, want := range []string{
		"── dev.framio.mawaqit started (pid 1468) ──",
		"Start proc 1468:dev.framio.mawaqit",
		"── dev.framio.mawaqit restarted (1468 → 2104) ──",
		"Start proc 2104:dev.framio.mawaqit",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
	if strings.Contains(out, "dev.framio.hello") || strings.Contains(out, "\x1b[") {
		t.Errorf("other apps or colors leaked:\n%s", out)
	}
}

func TestKeyAndText(t *testing.T) {
	r, err := runFake(t, "key", "home", "enter", "KEYCODE_BACK", "26")
	if err != nil || !strings.Contains(r.log, "input keyevent KEYCODE_HOME && input keyevent KEYCODE_ENTER && input keyevent KEYCODE_BACK && input keyevent 26") {
		t.Errorf("%v\n%s", err, r.log)
	}
	r, err = runFake(t, "text", "50% off", "now")
	if err != nil || !strings.Contains(r.log, `input text '50\%%soff%snow'`) {
		t.Errorf("%v\n%s", err, r.log)
	}
}

func TestUnknownCommandAndHelp(t *testing.T) {
	if _, err := runFake(t, "nope"); err == nil {
		t.Error("unknown command must fail")
	}
	r, err := runFake(t, "help")
	if err != nil || !strings.Contains(r.out.String(), "shot") {
		t.Errorf("%v %q", err, r.out.String())
	}
	if _, err := runFake(t, "gif"); !errors.Is(err, flag.ErrHelp) {
		t.Errorf("gif without a file: %v", err)
	}
}

// --- pure helpers ------------------------------------------------------------------

func TestNames(t *testing.T) {
	now := time.Date(2026, 10, 18, 15, 30, 12, 0, time.UTC)
	if got := captureName(now, "dev.framio.lab", "Timer flow #2!"); got != "153012_dev.framio.lab_timer-flow-2" {
		t.Errorf("got %q", got)
	}
	if got := captureName(now, "", ""); got != "153012" {
		t.Errorf("got %q", got)
	}
	if got := slug("  Ünïcode / ok  "); got != "n-code-ok" {
		t.Errorf("got %q", got)
	}
	dir := t.TempDir()
	first := freePath(dir, "a", ".png")
	os.WriteFile(first, nil, 0o644)
	if second := freePath(dir, "a", ".png"); filepath.Base(second) != "a-2.png" {
		t.Errorf("got %q", second)
	}
}

func TestParseArgs(t *testing.T) {
	e := &env{stderr: &bytes.Buffer{}}
	fs := newFlags(e, "shot")
	cp := fs.Bool("copy", false, "")
	n := fs.Int("burst", 1, "")
	pos, err := parseArgs(fs, []string{"my", "-copy", "label", "--burst", "3", "-v"})
	if err != nil || strings.Join(pos, " ") != "my label" || !*cp || *n != 3 || !e.verbose {
		t.Errorf("got %v %v %v %d %v", pos, err, *cp, *n, e.verbose)
	}
}

func TestBadging(t *testing.T) {
	b, err := os.ReadFile("internal/adb/testdata/badging-lab.txt")
	if err != nil {
		t.Fatal(err)
	}
	pkg, act, err := parseBadging(string(b))
	if err != nil || pkg != "dev.framio.lab" || act != "dev.framio.lab.LabActivity" {
		t.Errorf("got %q %q %v", pkg, act, err)
	}
	if pkg, act, _ := parseBadging("package: name='x.home' versionCode='1'\n"); pkg != "x.home" || act != "" {
		t.Errorf("no launchable activity: %q %q", pkg, act)
	}
	if _, _, err := parseBadging("ERROR"); err == nil {
		t.Error("garbage must fail")
	}
}

func TestInstallError(t *testing.T) {
	if err := installError("Performing Streamed Install\nSuccess\n"); err != nil {
		t.Error(err)
	}
	err := installError("adb: failed to install app.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package dev.x signatures do not match]\n")
	if err == nil || !strings.Contains(err.Error(), "fr uninstall") {
		t.Errorf("got %v", err)
	}
	err = installError("\tpkg: /data/local/tmp/app.apk\nFailure [INSTALL_FAILED_OLDER_SDK]\n")
	if err == nil || !strings.Contains(err.Error(), "minSdk") {
		t.Errorf("got %v", err)
	}
	if err := installError("error: device offline\n"); err == nil || !strings.Contains(err.Error(), "offline") {
		t.Errorf("got %v", err)
	}
}

func TestParseApps(t *testing.T) {
	out := "== dev.b\n    versionName=2.0\n    lastUpdateTime=2026-10-18 01:00:00\n== dev.a\n    versionName=0.1\n    versionName=0.0\n"
	apps := parseApps(out)
	if len(apps) != 2 || apps[0].pkg != "dev.a" || apps[0].version != "0.1" || apps[1].updated != "2026-10-18 01:00:00" {
		t.Errorf("got %+v", apps)
	}
}

func TestLogFilter(t *testing.T) {
	f := newLogFilter("dev.framio.lab", []string{"FramioLab"}, 100)
	line := func(pid int, tag, msg string) string {
		return "10-18 01:00:00.000  " + strconv.Itoa(pid) + "  " + strconv.Itoa(pid) + " I " + tag + ": " + msg
	}
	cases := []struct {
		s      string
		keep   bool
		banner string
	}{
		{line(100, "art", "gc"), true, ""},
		{line(999, "art", "other app"), false, ""},
		{line(999, "FramioLab", "tag from another pid"), true, ""},
		{line(339, "ActivityManager", "Process dev.framio.lab (pid 100) has died"), true, ""},
		{line(339, "ActivityManager", "Start proc 200:dev.framio.lab/u0a25 for activity"), true, "── dev.framio.lab restarted (100 → 200) ──"},
		{line(200, "art", "new process"), true, ""},
		{line(100, "art", "late line from the old process"), true, ""},
		{line(339, "ActivityManager", "Start proc 300:dev.framio.labx/u0a26 for activity"), false, ""},
		{"--------- beginning of main", false, ""},
	}
	for _, c := range cases {
		keep, _, banner := f.line(c.s)
		if keep != c.keep || banner != c.banner {
			t.Errorf("%q: keep %v banner %q", c.s, keep, banner)
		}
	}
	if b := f.setPID(200); b != "" {
		t.Errorf("same pid from ps must not print a banner: %q", b)
	}
	all := newLogFilter("", nil, 0)
	if keep, _, _ := all.line("--------- beginning of main"); !keep {
		t.Error("unfiltered keeps buffer headers")
	}
}

func TestMentions(t *testing.T) {
	for msg, want := range map[string]bool{
		"Process dev.x (pid 1) has died": true, "Start proc 2:dev.x/u0a1": true, "dev.x:remote": true, "dev.x": true,
		"dev.xy started": false, "com.dev.x": false, "dev.x2": false, "a dev.xy and dev.x.": false, "a dev.xy then dev.x/": true,
	} {
		if got := mentions(msg, "dev.x"); got != want {
			t.Errorf("mentions(%q) = %v", msg, got)
		}
	}
}

func TestLogcatSince(t *testing.T) {
	got, err := logcatSince("10-18 01:02:55\n", 5*time.Minute)
	if err != nil || got != "10-18 00:57:55.000" {
		t.Errorf("got %q %v", got, err)
	}
	if got, _ := logcatSince("01-01 00:00:30", time.Minute); got != "12-31 23:59:30.000" {
		t.Errorf("year boundary: %q", got)
	}
	if _, err := logcatSince("garbage", time.Minute); err == nil {
		t.Error("garbage must fail")
	}
}

func TestFitSize(t *testing.T) {
	tests := []struct{ w, h, max, ww, wh int }{
		{1280, 800, 1152, 1152, 720},
		{1280, 800, 0, 1280, 800},
		{1280, 800, 2000, 1280, 800},
		{800, 1280, 1000, 624, 1000},
	}
	for _, tt := range tests {
		if w, h := fitSize(tt.w, tt.h, tt.max); w != tt.ww || h != tt.wh {
			t.Errorf("fitSize(%d,%d,%d) = %d,%d", tt.w, tt.h, tt.max, w, h)
		}
	}
}

func TestKeyCode(t *testing.T) {
	for in, want := range map[string]string{"home": "KEYCODE_HOME", "Back": "KEYCODE_BACK", "up": "KEYCODE_DPAD_UP",
		"KEYCODE_F1": "KEYCODE_F1", "camera": "KEYCODE_CAMERA", "26": "26"} {
		if got := keyCode(in); got != want {
			t.Errorf("keyCode(%q) = %q", in, got)
		}
	}
}

func TestScrcpyArgs(t *testing.T) {
	d := &adb.Device{Serial: "S1"}
	got := strings.Join(scrcpyArgs(d, &recOpts{fps: 30, bitrate: "4M", limit: 1500 * time.Millisecond, size: 1152, touches: true}, "x.mkv", false), " ")
	want := "--serial=S1 --no-audio --no-window --no-playback --max-fps=30 --video-bit-rate=4M --max-size=1152 --time-limit=2 --show-touches --record=x.mkv"
	if got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	if got := strings.Join(scrcpyArgs(d, &recOpts{}, "", true), " "); got != "--serial=S1 --no-audio" {
		t.Errorf("window: %s", got)
	}
}

func TestInfo(t *testing.T) {
	out := "model=Frame\nmanufacturer=ChipTrip\nandroid=6.0.1\nsdk=23\nabi=armeabi-v7a\nplatform=rk312x\n" +
		"size=Physical size: 1280x800\ndensity=Physical density: 160\n" +
		"ip=    inet 192.168.11.206/24 brd 192.168.11.255 scope global wlan0\nuptime=3725.5 7000.1\nsu=/system/xbin/su\n" +
		"MemTotal:        1023588 kB\nMemFree:          100000 kB\nCached:           200000 kB\n" +
		"  mCurrentFocus=Window{d207a49 u0 dev.framio.mawaqit/dev.framio.mawaqit.MawaqitActivity}\n"
	s := parseInfo(out).format("68384696")
	for _, want := range []string{"ChipTrip Frame · 68384696", "6.0.1 (API 23)", "1280x800 @ 160 dpi", "192.168.11.206",
		"dev.framio.mawaqit/.MawaqitActivity", "292 MB available of 999 MB", "1h2m5s", "su at /system/xbin/su"} {
		if !strings.Contains(s, want) {
			t.Errorf("missing %q in:\n%s", want, s)
		}
	}
	if got := dfFree("Filesystem Size Used Free Blksize\n/data 27.0G 623.4M 26.4G 4096\n"); got != "26.4G" {
		t.Errorf("df android 6: %q", got)
	}
	if got := dfFree("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-0 1000 10 990 1% /data\n"); got != "990 KB" {
		t.Errorf("df toybox: %q", got)
	}
}

func TestInputText(t *testing.T) {
	if got := inputText("a b%c"); got != `a%sb\%c` {
		t.Errorf("got %q", got)
	}
}
