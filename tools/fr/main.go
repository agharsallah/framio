// Command fr captures and controls an Android device over adb: screenshots,
// recordings, a live mirror window, the build → install → launch → logs loop,
// touch and key input. It combines adb, scrcpy and ffmpeg; see PLAN.md.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sort"
	"strings"
	"syscall"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

type command struct {
	name, group, usage, help string
	run                      func(ctx context.Context, e *env, args []string) error
}

var commands []command

func init() {
	commands = []command{
		{"shot", "Capture", "[label] [--copy] [--open] [--png] [--burst N --every 1s]", "screenshot to PNG, prints the path", cmdShot},
		{"rec", "Capture", "[label] [--time 30s] [--size N] [--fps 30] [--bitrate 4M] [--touches] [--show] [--gif] [--engine device --stamp]", "record until Ctrl-C or --time", cmdRec},
		{"rec start", "Capture", "[label] [same flags as rec]", "start a background recording", nil},
		{"rec stop", "Capture", "", "stop it and save the MP4", nil},
		{"gif", "Capture", "<video> [--width 640] [--fps 12]", "make a GIF next to the video", cmdGif},
		{"mirror", "View and control", "[--rec] [--top] [--off] [--size N]", "live window: click = tap, type = keys, drop an APK = install", cmdMirror},
		{"tap", "View and control", "<x> <y> | <text> [--nth N]", "tap a point, or the element whose text matches", cmdTap},
		{"find", "View and control", "[text]", "list on-screen elements (matching text) with bounds", cmdFind},
		{"swipe", "View and control", "<x1> <y1> <x2> <y2> [ms]", "swipe", cmdSwipe},
		{"long", "View and control", "<x> <y> [ms]", "long-press (default 1000 ms)", cmdLong},
		{"text", "View and control", "<text>", "type text", cmdText},
		{"key", "View and control", "home|back|menu|power|wakeup|sleep|enter|up|down|left|right|<KEYCODE>…", "send key events", cmdKey},
		{"run", "Apps", "[dir] [--release] [--no-logs] [--task T]", "build, install, launch, follow logs", cmdRun},
		{"install", "Apps", "<apk|dir>… [--launch] [--downgrade]", "install APKs (a dir means its newest built APK)", cmdInstall},
		{"start", "Apps", "<pkg>[/activity]", "launch, prints the launch time", cmdStart},
		{"stop", "Apps", "<pkg>", "force-stop", cmdStop},
		{"restart", "Apps", "<pkg>", "force-stop, then launch", cmdRestart},
		{"clear", "Apps", "<pkg>", "clear the app's data", cmdClear},
		{"uninstall", "Apps", "<pkg>", "uninstall", cmdUninstall},
		{"apps", "Apps", "", "installed user apps with versions", cmdApps},
		{"logs", "Apps", "[pkg] [--tag T]… [--since 5m] [--dump] [--raw]", "logs of one app, across restarts", cmdLogs},
		{"info", "Device", "", "model, Android, IP, screen, foreground app, RAM, uptime", cmdInfo},
		{"doctor", "Device", "", "check the Mac and the device, print fixes", cmdDoctor},
		{"bug", "Device", "[label]", "evidence folder: screenshot, logs, dumpsys, top", cmdBug},
		{"wait", "Device", "", "wait until the device has booted", cmdWait},
		{"reboot", "Device", "[--wait]", "reboot", cmdReboot},
		{"screen", "Device", "on|off|<0-255>", "backlight (root) or wake/sleep", cmdScreen},
		{"sh", "Device", "[--root] <command…>", "shell command with a real exit code", cmdSh},
		{"completion", "Other", "zsh", "print the zsh completion script", cmdCompletion},
		{"version", "Other", "", "print the version", cmdVersion},
		{"help", "Other", "", "this help", nil},
	}
}

// env holds the global flags and the lazily picked device.
type env struct {
	serial  string
	verbose bool
	out     string
	locked  bool // holds $FR_LOCK
	dev     *adb.Device
	stdout  io.Writer
	stderr  io.Writer
}

func (e *env) device(ctx context.Context) (*adb.Device, error) {
	if e.dev != nil {
		return e.dev, nil
	}
	bin, err := adb.FindBin()
	if err != nil {
		return nil, err
	}
	d, err := adb.Pick(ctx, bin, e.serial)
	if err != nil {
		return nil, err
	}
	d.Verbose, d.Log = e.verbose, e.stderr
	e.dev = d
	return d, nil
}

func (e *env) logf(format string, a ...any) {
	if e.verbose {
		fmt.Fprintf(e.stderr, "  "+format+"\n", a...)
	}
}

// exitError ends fr with a specific exit code and no message (fr sh).
type exitError int

func (e exitError) Error() string { return fmt.Sprintf("exit %d", int(e)) }

// newFlags returns a FlagSet with the global flags, usable after the command name too.
func newFlags(e *env, name string) *flag.FlagSet {
	fs := flag.NewFlagSet("fr "+name, flag.ContinueOnError)
	fs.SetOutput(e.stderr)
	fs.StringVar(&e.serial, "s", e.serial, "device serial")
	fs.BoolVar(&e.verbose, "v", e.verbose, "print underlying commands")
	fs.StringVar(&e.out, "o", e.out, "captures folder")
	fs.Usage = func() {
		for _, c := range commands {
			if c.name == name {
				fmt.Fprintf(e.stderr, "usage: fr %s %s\n  %s\n", c.name, c.usage, c.help)
			}
		}
		fs.PrintDefaults()
	}
	return fs
}

// parseArgs parses flags anywhere among the positional arguments.
func parseArgs(fs *flag.FlagSet, args []string) ([]string, error) {
	var pos []string
	for {
		if err := fs.Parse(args); err != nil {
			return nil, err
		}
		args = fs.Args()
		if len(args) == 0 {
			return pos, nil
		}
		pos = append(pos, args[0])
		args = args[1:]
	}
}

func usage(w io.Writer) {
	fmt.Fprintln(w, "fr: capture and control an Android device over adb")
	fmt.Fprintln(w, "\nusage: fr [-s serial] [-v] [-o dir] <command> [args]")
	group := ""
	for _, c := range commands {
		if c.group != group {
			group = c.group
			fmt.Fprintf(w, "\n%s\n", group)
		}
		fmt.Fprintf(w, "  %-10s %s\n  %-10s   %s\n", c.name, c.usage, "", c.help)
	}
	fmt.Fprintln(w, "\nEnvironment: ADB (adb binary), ANDROID_SERIAL, FR_OUT (captures folder), ANDROID_HOME (aapt2),")
	fmt.Fprintln(w, "             FR_LOCK (lock directory shared by everyone driving the device, e.g. /tmp/framio-frame.lock)")
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	e := &env{stdout: os.Stdout, stderr: os.Stderr}
	err := run(ctx, e, os.Args[1:])
	var code exitError
	switch {
	case err == nil:
	case errors.As(err, &code):
		os.Exit(int(code))
	case errors.Is(err, flag.ErrHelp):
		os.Exit(2)
	default:
		fmt.Fprintln(os.Stderr, "fr:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "")
	fs.Usage = func() { usage(e.stderr) }
	if err := fs.Parse(args); err != nil {
		return err
	}
	args = fs.Args()
	if len(args) == 0 || args[0] == "help" || args[0] == "-h" || args[0] == "--help" {
		usage(e.stdout)
		return nil
	}
	for _, c := range commands {
		if c.name == args[0] && c.run != nil {
			if lockingCommands[c.name] {
				release, err := e.lock(ctx, lockWhat(c.name, args[1:]))
				if err != nil {
					return err
				}
				defer release()
			}
			return c.run(ctx, e, args[1:])
		}
	}
	return fmt.Errorf("unknown command %q, see fr help", args[0])
}

// cleanupCtx is for restoring device state after Ctrl-C cancelled the main context.
func cleanupCtx(ctx context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
}

func cmdCompletion(ctx context.Context, e *env, args []string) error {
	if len(args) != 1 || args[0] != "zsh" {
		return errors.New("usage: fr completion zsh   (then: fr completion zsh > \"${fpath[1]}/_fr\")")
	}
	var names []string
	seen := map[string]bool{}
	for _, c := range commands {
		n, _, _ := strings.Cut(c.name, " ")
		if !seen[n] {
			seen[n] = true
			names = append(names, fmt.Sprintf("    '%s:%s'", n, strings.ReplaceAll(c.help, "'", "")))
		}
	}
	sort.Strings(names)
	fmt.Fprintf(e.stdout, `#compdef fr
# generated by: fr completion zsh
_fr() {
  local -a cmds
  cmds=(
%s
  )
  if (( CURRENT == 2 )); then
    _describe 'command' cmds
  else
    case $words[2] in
      install|gif) _files ;;
      run) _files -/ ;;
      start|stop|restart|clear|uninstall|logs)
        local -a pkgs; pkgs=(${(f)"$(adb shell pm list packages -3 2>/dev/null | tr -d '\r' | sed 's/^package://')"})
        _describe 'package' pkgs ;;
      key) _values 'key' home back menu power wakeup sleep enter up down left right ;;
      screen) _values 'state' on off ;;
      rec) _values 'action' start stop ;;
    esac
  fi
}
_fr "$@"
`, strings.Join(names, "\n"))
	return nil
}
