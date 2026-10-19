package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

func cmdRun(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "run")
	release := fs.Bool("release", false, "assembleRelease instead of assembleDebug")
	noLogs := fs.Bool("no-logs", false, "don't follow the app's logs")
	task := fs.String("task", "", "Gradle task to run instead of assembleDebug/assembleRelease")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	dir := "."
	if len(pos) > 0 {
		dir = pos[0]
	}
	root, err := gradleRoot(dir)
	if err != nil {
		return err
	}
	variant, t := "debug", "assembleDebug"
	if *release {
		variant, t = "release", "assembleRelease"
	}
	if *task != "" {
		t = *task
		if strings.Contains(strings.ToLower(t), "release") {
			variant = "release"
		}
	}
	d, err := e.device(ctx) // fail before a long build if no device
	if err != nil {
		return err
	}

	start := time.Now()
	if err := e.gradle(ctx, root, t); err != nil {
		return err
	}
	buildTime := time.Since(start)
	apk, err := newestAPK(root, variant)
	if err != nil {
		return err
	}
	pkg, activity, err := badging(ctx, root, apk)
	if err != nil {
		return err
	}

	unlock, err := e.lock(ctx, "run "+pkg) // only around install and launch, not the build or the logs
	if err != nil {
		return err
	}
	defer unlock()
	start = time.Now()
	if err := installAPK(ctx, d, apk, false); err != nil {
		return err
	}
	installTime := time.Since(start)
	launchStart := time.Now()
	if _, err := d.MustShell(ctx, "am force-stop "+adb.Quote(pkg)); err != nil {
		return err
	}
	am, err := launch(ctx, d, pkg, activity)
	if err != nil {
		return err
	}
	unlock()
	fmt.Fprintf(e.stdout, "%s  build %s · install %s · launch %s\n", pkg, ms(buildTime), ms(installTime), launchTime(am))
	if *noLogs {
		return nil
	}
	return e.followLogs(ctx, d, logOpts{pkg: pkg, since: time.Since(launchStart) + 2*time.Second})
}

func launchTime(am adb.AmStart) string {
	if am.TotalTime < 0 {
		return "?"
	}
	return fmt.Sprintf("%d ms", am.TotalTime)
}

// gradleRoot is dir or its nearest parent that has gradlew.
func gradleRoot(dir string) (string, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return "", err
	}
	for d := abs; ; d = filepath.Dir(d) {
		if exists(filepath.Join(d, "gradlew")) {
			return d, nil
		}
		if filepath.Dir(d) == d {
			return "", fmt.Errorf("no gradlew in %s or its parents", abs)
		}
	}
}

func (e *env) gradle(ctx context.Context, root, task string) error {
	cmd := exec.CommandContext(ctx, "./gradlew", "--console=plain", "-q", task)
	cmd.Dir = root
	cmd.Stdout, cmd.Stderr = e.stderr, e.stderr
	cmd.Env = os.Environ()
	if jh, err := javaHome(ctx); err == nil && jh != "" {
		cmd.Env = append(cmd.Env, "JAVA_HOME="+jh)
	}
	fmt.Fprintf(e.stderr, "gradle %s in %s…\n", task, display(root))
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("gradle %s failed: %w", task, err)
	}
	return nil
}

var javaVersionRE = regexp.MustCompile(`version "(?:1\.)?(\d+)`)

// javaHome keeps $JAVA_HOME when it is JDK 17+, otherwise asks macOS for JDK 21, then 17+.
func javaHome(ctx context.Context) (string, error) {
	if jh := os.Getenv("JAVA_HOME"); jh != "" && javaMajor(ctx, filepath.Join(jh, "bin", "java")) >= 17 {
		return jh, nil
	}
	for _, v := range []string{"21", "17+"} {
		out, err := exec.CommandContext(ctx, "/usr/libexec/java_home", "-v", v).Output()
		if err == nil {
			return strings.TrimSpace(string(out)), nil
		}
	}
	return "", errors.New("no JDK 17+ found (Gradle needs one)")
}

func javaMajor(ctx context.Context, java string) int {
	out, err := exec.CommandContext(ctx, java, "-version").CombinedOutput()
	if err != nil {
		return 0
	}
	m := javaVersionRE.FindStringSubmatch(string(out))
	if m == nil {
		return 0
	}
	n, _ := strconv.Atoi(m[1])
	return n
}

// newestAPK returns the most recently built APK of a variant ("" = any) in any module.
func newestAPK(root, variant string) (string, error) {
	if variant == "" {
		variant = "*"
	}
	matches, _ := filepath.Glob(filepath.Join(root, "*", "build", "outputs", "apk", variant, "*.apk"))
	if len(matches) == 0 {
		return "", fmt.Errorf("no %s APK under %s/*/build/outputs/apk: build first", strings.Trim(variant, "*"), display(root))
	}
	sort.Slice(matches, func(i, j int) bool { return mtime(matches[i]).After(mtime(matches[j])) })
	return matches[0], nil
}

func mtime(p string) time.Time {
	st, err := os.Stat(p)
	if err != nil {
		return time.Time{}
	}
	return st.ModTime()
}

// sdkDir finds the Android SDK: $ANDROID_HOME, $ANDROID_SDK_ROOT, sdk.dir in
// local.properties, then the usual install locations.
func sdkDir(root string) string {
	for _, v := range []string{"ANDROID_HOME", "ANDROID_SDK_ROOT"} {
		if d := os.Getenv(v); d != "" && exists(d) {
			return d
		}
	}
	if root != "" {
		if f, err := os.Open(filepath.Join(root, "local.properties")); err == nil {
			defer f.Close()
			s := bufio.NewScanner(f)
			for s.Scan() {
				if v, ok := strings.CutPrefix(strings.TrimSpace(s.Text()), "sdk.dir="); ok && exists(v) {
					return v
				}
			}
		}
	}
	home, _ := os.UserHomeDir()
	for _, d := range []string{"/opt/homebrew/share/android-commandlinetools", filepath.Join(home, "Library/Android/sdk")} {
		if exists(d) {
			return d
		}
	}
	return ""
}

// aapt2Path returns aapt2 from the newest build-tools of the SDK.
func aapt2Path(root string) (string, error) {
	if p, err := exec.LookPath("aapt2"); err == nil {
		return p, nil
	}
	sdk := sdkDir(root)
	matches, _ := filepath.Glob(filepath.Join(sdk, "build-tools", "*", "aapt2"))
	if len(matches) == 0 {
		return "", errors.New("aapt2 not found: install build-tools with sdkmanager, or set ANDROID_HOME")
	}
	sort.Slice(matches, func(i, j int) bool { return versionLess(matches[j], matches[i]) })
	return matches[0], nil
}

// versionLess compares the build-tools version directories of two paths.
func versionLess(a, b string) bool {
	va := strings.Split(filepath.Base(filepath.Dir(a)), ".")
	vb := strings.Split(filepath.Base(filepath.Dir(b)), ".")
	for i := 0; i < len(va) && i < len(vb); i++ {
		x, _ := strconv.Atoi(va[i])
		y, _ := strconv.Atoi(vb[i])
		if x != y {
			return x < y
		}
	}
	return len(va) < len(vb)
}

var (
	badgingPkgRE = regexp.MustCompile(`(?m)^package: name='([^']+)'`)
	badgingActRE = regexp.MustCompile(`(?m)^launchable-activity: name='([^']+)'`)
)

// parseBadging reads the package and the launchable activity ("" if none) from `aapt2 dump badging`.
func parseBadging(out string) (pkg, activity string, err error) {
	m := badgingPkgRE.FindStringSubmatch(out)
	if m == nil {
		return "", "", errors.New("aapt2 dump badging: no package name")
	}
	if a := badgingActRE.FindStringSubmatch(out); a != nil {
		activity = a[1]
	}
	return m[1], activity, nil
}

func badging(ctx context.Context, root, apk string) (pkg, activity string, err error) {
	aapt2, err := aapt2Path(root)
	if err != nil {
		return "", "", err
	}
	out, err := exec.CommandContext(ctx, aapt2, "dump", "badging", apk).Output()
	if err != nil {
		return "", "", fmt.Errorf("aapt2 dump badging %s: %w", apk, err)
	}
	return parseBadging(string(out))
}

var installFailRE = regexp.MustCompile(`(INSTALL_[A-Z_]+|DELETE_[A-Z_]+)`)

var installHints = map[string]string{
	"INSTALL_FAILED_UPDATE_INCOMPATIBLE":             "signed with a different key than the installed app: fr uninstall <pkg> first",
	"INSTALL_FAILED_VERSION_DOWNGRADE":               "older versionCode than the installed app: add --downgrade",
	"INSTALL_FAILED_OLDER_SDK":                       "minSdk is higher than the device's Android version",
	"INSTALL_FAILED_INSUFFICIENT_STORAGE":            "the device is out of space",
	"INSTALL_PARSE_FAILED_NO_CERTIFICATES":           "the APK is not signed",
	"INSTALL_FAILED_NO_MATCHING_ABIS":                "the APK has no native code for the device's ABI",
	"INSTALL_FAILED_TEST_ONLY":                       "test-only APK: install it with adb install -t",
	"INSTALL_FAILED_DUPLICATE_PERMISSION":            "another app already defines one of its permissions",
	"INSTALL_FAILED_CONFLICTING_PROVIDER":            "another app already uses one of its content provider authorities",
	"INSTALL_PARSE_FAILED_MANIFEST_MALFORMED":        "AndroidManifest.xml is not valid for this Android version",
	"INSTALL_FAILED_MISSING_SHARED_LIBRARY":          "the device lacks a library the manifest requires",
	"INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES": "signed with a different key than the installed app: fr uninstall <pkg> first",
}

// installError turns `adb install` output into nil or an error with a hint.
func installError(out string) error {
	if strings.Contains(out, "Success") && !strings.Contains(out, "Failure") {
		return nil
	}
	code := ""
	if m := installFailRE.FindStringSubmatch(out); m != nil {
		code = m[1]
	}
	msg := strings.TrimSpace(out)
	if code != "" {
		msg = code
		if h, ok := installHints[code]; ok {
			msg += ": " + h
		}
	}
	return fmt.Errorf("install failed: %s", msg)
}

func installAPK(ctx context.Context, d *adb.Device, apk string, downgrade bool) error {
	args := []string{"install", "-r"}
	if downgrade {
		args = append(args, "-d")
	}
	out, err := d.Run(ctx, append(args, apk)...)
	if ierr := installError(out); ierr != nil {
		return ierr
	}
	return err
}

// launch starts an app and waits for its first frame. Without an activity it
// resolves the LAUNCHER intent, then the HOME intent (launcher-only apps),
// limited to the package: Android 6 has no `cmd package resolve-activity`.
func launch(ctx context.Context, d *adb.Device, pkg, activity string) (adb.AmStart, error) {
	var tries []string
	if activity != "" {
		tries = append(tries, "am start -W -n "+adb.Quote(pkg+"/"+activity))
	}
	for _, cat := range []string{"LAUNCHER", "HOME"} {
		tries = append(tries, "am start -W -a android.intent.action.MAIN -c android.intent.category."+cat+" -p "+adb.Quote(pkg))
	}
	var r adb.AmStart
	for _, script := range tries {
		out, err := d.MustShell(ctx, script)
		if err != nil {
			return r, err
		}
		r = adb.ParseAmStart(out)
		if r.Error == "" {
			return r, nil
		}
	}
	return r, fmt.Errorf("could not start %s: %s", pkg, r.Error)
}

func cmdInstall(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "install")
	launchIt := fs.Bool("launch", false, "launch after installing")
	downgrade := fs.Bool("downgrade", false, "allow a lower versionCode (adb install -d)")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	if len(pos) == 0 {
		fs.Usage()
		return flag.ErrHelp
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	for _, p := range pos {
		apk, root := p, ""
		if st, err := os.Stat(p); err == nil && st.IsDir() {
			if root, err = gradleRoot(p); err != nil {
				return err
			}
			if apk, err = newestAPK(root, ""); err != nil {
				return err
			}
		} else if err != nil {
			return err
		}
		start := time.Now()
		if err := installAPK(ctx, d, apk, *downgrade); err != nil {
			return fmt.Errorf("%s: %w", display(apk), err)
		}
		fmt.Fprintf(e.stdout, "installed %s  (%s)\n", display(apk), ms(time.Since(start)))
		if *launchIt {
			pkg, activity, err := badging(ctx, root, apk)
			if err != nil {
				return err
			}
			am, err := launch(ctx, d, pkg, activity)
			if err != nil {
				return err
			}
			fmt.Fprintf(e.stdout, "launched %s  (%s)\n", am.Activity, launchTime(am))
		}
	}
	return nil
}

// pkgArg reads one "pkg" or "pkg/activity" argument.
func pkgArg(name string, args []string) (pkg, activity string, err error) {
	if len(args) != 1 || strings.HasPrefix(args[0], "-") {
		return "", "", fmt.Errorf("usage: fr %s <pkg>", name)
	}
	pkg, activity, _ = strings.Cut(args[0], "/")
	if strings.HasPrefix(activity, ".") {
		activity = pkg + activity
	}
	return pkg, activity, nil
}

func cmdStart(ctx context.Context, e *env, args []string) error {
	pkg, activity, err := pkgArg("start", args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	am, err := launch(ctx, d, pkg, activity)
	if err != nil {
		return err
	}
	note := ""
	if am.Warning != "" {
		note = " (was already running: brought to front)"
	}
	fmt.Fprintf(e.stdout, "launched %s  (%s)%s\n", am.Activity, launchTime(am), note)
	return nil
}

func cmdStop(ctx context.Context, e *env, args []string) error {
	pkg, _, err := pkgArg("stop", args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	_, err = d.MustShell(ctx, "am force-stop "+adb.Quote(pkg))
	return err
}

func cmdRestart(ctx context.Context, e *env, args []string) error {
	if err := cmdStop(ctx, e, args); err != nil {
		return err
	}
	return cmdStart(ctx, e, args)
}

func cmdClear(ctx context.Context, e *env, args []string) error {
	pkg, _, err := pkgArg("clear", args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	out, err := d.MustShell(ctx, "pm clear "+adb.Quote(pkg))
	if err == nil && !strings.Contains(out, "Success") {
		err = fmt.Errorf("pm clear: %s", strings.TrimSpace(out))
	}
	return err
}

func cmdUninstall(ctx context.Context, e *env, args []string) error {
	pkg, _, err := pkgArg("uninstall", args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	out, err := d.Run(ctx, "uninstall", pkg)
	if !strings.Contains(out, "Success") {
		return fmt.Errorf("uninstall %s: %s", pkg, strings.TrimSpace(out))
	}
	return err
}

type appInfo struct{ pkg, version, updated string }

// parseApps reads the output of the script in cmdApps.
func parseApps(out string) []appInfo {
	var apps []appInfo
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(line, "== "):
			apps = append(apps, appInfo{pkg: strings.TrimPrefix(line, "== ")})
		case len(apps) == 0:
		case strings.HasPrefix(line, "versionName=") && apps[len(apps)-1].version == "":
			apps[len(apps)-1].version = strings.TrimPrefix(line, "versionName=")
		case strings.HasPrefix(line, "lastUpdateTime=") && apps[len(apps)-1].updated == "":
			apps[len(apps)-1].updated = strings.TrimPrefix(line, "lastUpdateTime=")
		}
	}
	sort.Slice(apps, func(i, j int) bool { return apps[i].pkg < apps[j].pkg })
	return apps
}

func cmdApps(ctx context.Context, e *env, args []string) error {
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	out, err := d.MustShell(ctx, `for p in $(pm list packages -3); do p=${p#package:}; echo "== $p"; dumpsys package "$p" | grep -E 'versionName=|lastUpdateTime='; done`)
	if err != nil {
		return err
	}
	apps := parseApps(out)
	if len(apps) == 0 {
		fmt.Fprintln(e.stdout, "no user apps")
	}
	for _, a := range apps {
		fmt.Fprintf(e.stdout, "%-36s %-10s %s\n", a.pkg, a.version, a.updated)
	}
	return nil
}
