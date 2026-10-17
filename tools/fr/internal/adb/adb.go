// Package adb runs commands on one Android device through the adb binary.
//
// It hides the quirks of old devices such as the Veidoo F10 (Android 6.0.1):
// without the shell protocol, `adb shell` always exits 0, mixes stderr into
// stdout and ends lines with \r\n. Shell appends an exit-code marker and
// strips the \r; binary data only goes through ExecOut.
package adb

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// Device is one attached device, reached through one adb binary.
type Device struct {
	Bin     string    // path to adb
	Serial  string    // passed as -s
	Verbose bool      // log each command and its duration to Log
	Log     io.Writer // defaults to os.Stderr
}

// Attached is one line of `adb devices -l`.
type Attached struct {
	Serial, State, Model string
}

// FindBin returns $ADB, or adb from PATH.
func FindBin() (string, error) {
	if b := os.Getenv("ADB"); b != "" {
		return b, nil
	}
	b, err := exec.LookPath("adb")
	if err != nil {
		return "", errors.New("adb not found: install platform-tools or set $ADB")
	}
	return b, nil
}

// List runs `adb devices -l`.
func List(ctx context.Context, bin string) ([]Attached, error) {
	out, err := exec.CommandContext(ctx, bin, "devices", "-l").CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("adb devices: %v: %s", err, bytes.TrimSpace(out))
	}
	return ParseDevices(string(out)), nil
}

// Pick chooses the device: serial if given, else $ANDROID_SERIAL, else the only one attached.
func Pick(ctx context.Context, bin, serial string) (*Device, error) {
	if serial == "" {
		serial = os.Getenv("ANDROID_SERIAL")
	}
	all, err := List(ctx, bin)
	if err != nil {
		return nil, err
	}
	var found []Attached
	for _, a := range all {
		if serial == "" || a.Serial == serial {
			found = append(found, a)
		}
	}
	switch {
	case len(found) == 0 && serial != "":
		return nil, fmt.Errorf("device %s not attached (adb devices: %s)", serial, describe(all))
	case len(found) == 0:
		return nil, errors.New("no device attached: plug in USB and enable USB debugging")
	case len(found) > 1:
		return nil, fmt.Errorf("%d devices attached, pick one with -s or $ANDROID_SERIAL: %s", len(found), describe(found))
	}
	a := found[0]
	switch a.State {
	case "device":
	case "unauthorized":
		return nil, fmt.Errorf("%s is unauthorized: accept the USB debugging prompt on the device", a.Serial)
	default:
		return nil, fmt.Errorf("%s is %s: replug the cable or run `adb kill-server`", a.Serial, a.State)
	}
	return &Device{Bin: bin, Serial: a.Serial}, nil
}

func describe(all []Attached) string {
	if len(all) == 0 {
		return "none"
	}
	var s []string
	for _, a := range all {
		s = append(s, a.Serial+" ("+a.State+")")
	}
	return strings.Join(s, ", ")
}

// Command returns an adb command for this device, for callers that stream its output.
func (d *Device) Command(ctx context.Context, args ...string) *exec.Cmd {
	all := args
	if d.Serial != "" {
		all = append([]string{"-s", d.Serial}, args...)
	}
	return exec.CommandContext(ctx, d.Bin, all...)
}

func (d *Device) logf(start time.Time, args []string) {
	if !d.Verbose {
		return
	}
	w := d.Log
	if w == nil {
		w = os.Stderr
	}
	fmt.Fprintf(w, "  $ adb %s  (%s)\n", strings.Join(quoteAll(args), " "), time.Since(start).Round(time.Millisecond))
}

func quoteAll(args []string) []string {
	q := make([]string, len(args))
	for i, a := range args {
		if a == "" || strings.ContainsAny(a, " \t\n'\"|;&$<>()*?") {
			q[i] = Quote(a)
		} else {
			q[i] = a
		}
	}
	return q
}

// Run runs an adb host command (install, pull, reboot…) and returns its output with \r removed.
func (d *Device) Run(ctx context.Context, args ...string) (string, error) {
	start := time.Now()
	out, err := d.Command(ctx, args...).CombinedOutput()
	d.logf(start, args)
	s := strings.ReplaceAll(string(out), "\r", "")
	if err != nil {
		return s, fmt.Errorf("adb %s: %v: %s", args[0], err, strings.TrimSpace(s))
	}
	return s, nil
}

const rcMarker = "::fr-rc="

// Shell runs script with the device's sh and returns its output (stdout and
// stderr, \r removed) and its real exit code.
func (d *Device) Shell(ctx context.Context, script string) (string, int, error) {
	start := time.Now()
	// A subshell, so that `exit N` in script still reaches the marker.
	out, err := d.Command(ctx, "shell", "(\n"+script+"\n)\necho \""+rcMarker+"$?\"").Output()
	d.logf(start, []string{"shell", script})
	if err != nil {
		if ctx.Err() != nil {
			return "", -1, ctx.Err()
		}
		return "", -1, fmt.Errorf("adb shell: %v", err)
	}
	text, rc, ok := ParseRC(string(out))
	if !ok {
		return text, -1, errors.New("adb shell: no exit code in output (device disconnected?)")
	}
	return text, rc, nil
}

// MustShell is Shell that turns a non-zero exit code into an error.
func (d *Device) MustShell(ctx context.Context, script string) (string, error) {
	out, rc, err := d.Shell(ctx, script)
	if err != nil {
		return out, err
	}
	if rc != 0 {
		return out, fmt.Errorf("%s: exit %d: %s", firstWord(script), rc, strings.TrimSpace(out))
	}
	return out, nil
}

func firstWord(s string) string {
	if f := strings.Fields(s); len(f) > 0 {
		return f[0]
	}
	return s
}

// ExecOut runs script and returns its raw stdout (binary-safe).
func (d *Device) ExecOut(ctx context.Context, script string) ([]byte, error) {
	start := time.Now()
	var stderr bytes.Buffer
	cmd := d.Command(ctx, "exec-out", script)
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	d.logf(start, []string{"exec-out", script})
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("adb exec-out: %v: %s", err, strings.TrimSpace(stderr.String()))
	}
	return out, nil
}

// Root runs script as root. One line goes to `su -c`; several lines are pushed
// as a script, because SuperSU ignores a multi-line `su -c`.
func (d *Device) Root(ctx context.Context, script string) (string, int, error) {
	script = strings.TrimSpace(script)
	if !strings.Contains(script, "\n") {
		return d.Shell(ctx, "su -c "+Quote(script))
	}
	f, err := os.CreateTemp("", "fr-root-*.sh")
	if err != nil {
		return "", -1, err
	}
	defer os.Remove(f.Name())
	if _, err := f.WriteString(script + "\n"); err != nil {
		f.Close()
		return "", -1, err
	}
	f.Close()
	remote := "/data/local/tmp/" + filepath.Base(f.Name())
	if _, err := d.Run(ctx, "push", f.Name(), remote); err != nil {
		return "", -1, err
	}
	out, rc, err := d.Shell(ctx, "su -c "+Quote("sh "+remote)+"; r=$?; rm -f "+remote+"; (exit $r)")
	return out, rc, err
}

// PID returns the PID of the process named exactly pkg. It never uses pidof:
// toybox pidof on Android 6 prints every PID, even for unknown names.
func (d *Device) PID(ctx context.Context, pkg string) (int, bool, error) {
	q := Quote(pkg)
	// Android 6 toolbox: `ps NAME` filters by name. Android 8+: `ps -A` lists everything.
	out, _, err := d.Shell(ctx, "ps "+q+" 2>/dev/null; ps -A 2>/dev/null | grep -F "+q)
	if err != nil {
		return 0, false, err
	}
	pid, ok := ParsePS(out, pkg)
	return pid, ok, nil
}

// Focus returns the package and activity of the focused window.
func (d *Device) Focus(ctx context.Context) (pkg, activity string, err error) {
	out, _, err := d.Shell(ctx, "dumpsys window windows | grep -E 'mCurrentFocus'")
	if err != nil {
		return "", "", err
	}
	pkg, activity = ParseFocus(out)
	return pkg, activity, nil
}

// Prop returns one system property.
func (d *Device) Prop(ctx context.Context, name string) (string, error) {
	out, _, err := d.Shell(ctx, "getprop "+Quote(name))
	return strings.TrimSpace(out), err
}

// Features returns the adb transport features (empty on Android 6: no shell_v2).
func (d *Device) Features(ctx context.Context) ([]string, error) {
	out, err := d.Run(ctx, "features")
	if err != nil {
		return nil, err
	}
	return strings.Fields(out), nil
}

// Quote wraps s in single quotes for the device shell.
func Quote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}
