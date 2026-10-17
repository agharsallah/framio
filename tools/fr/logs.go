package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

type logOpts struct {
	pkg   string
	tags  []string
	since time.Duration // 0: from now on (or the whole buffer with dump)
	dump  bool          // print and exit instead of following
	raw   bool          // no colors
}

type multiFlag []string

func (m *multiFlag) String() string     { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error { *m = append(*m, v); return nil }

func cmdLogs(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "logs")
	var o logOpts
	var tags multiFlag
	fs.Var(&tags, "tag", "also show this tag (repeatable)")
	fs.DurationVar(&o.since, "since", 0, "start this far back (e.g. 30s, 5m)")
	fs.BoolVar(&o.dump, "dump", false, "print what is in the buffer and exit")
	fs.BoolVar(&o.raw, "raw", false, "no colors")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	if len(pos) > 1 {
		return fmt.Errorf("usage: fr logs [pkg] [--tag T]… [--since 5m] [--dump]")
	}
	if len(pos) == 1 {
		o.pkg = pos[0]
	}
	o.tags = tags
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	return e.followLogs(ctx, d, o)
}

// logFilter keeps the lines of one app across restarts. It knows every PID the
// app has had: from `ps`, and from ActivityManager's "Start proc" lines, which
// switch to a new process as soon as it is logged.
type logFilter struct {
	mu   sync.Mutex
	pkg  string
	tags map[string]bool
	pids map[int]bool
	pid  int
}

func newLogFilter(pkg string, tags []string, pid int) *logFilter {
	f := &logFilter{pkg: pkg, tags: map[string]bool{}, pids: map[int]bool{}}
	for _, t := range tags {
		f.tags[t] = true
	}
	if pid > 0 {
		f.pids[pid] = true
		f.pid = pid
	}
	return f
}

// setPID records the app's current PID and returns a banner when it changed.
func (f *logFilter) setPID(pid int) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if pid <= 0 || pid == f.pid {
		return ""
	}
	old := f.pid
	f.pid = pid
	f.pids[pid] = true
	if old == 0 {
		return fmt.Sprintf("── %s started (pid %d) ──", f.pkg, pid)
	}
	return fmt.Sprintf("── %s restarted (%d → %d) ──", f.pkg, old, pid)
}

// line decides whether to print a logcat line, and returns a banner to print first.
func (f *logFilter) line(s string) (keep bool, l adb.LogLine, banner string) {
	l, ok := adb.ParseLogLine(s)
	unfiltered := f.pkg == "" && len(f.tags) == 0
	if !ok {
		return unfiltered && strings.TrimSpace(s) != "", l, ""
	}
	if f.pkg != "" {
		if pid, pkg, ok := adb.ParseStartProc(l); ok && pkg == f.pkg {
			banner = f.setPID(pid)
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case unfiltered, f.tags[l.Tag], f.pids[l.PID]:
		return true, l, banner
	case f.pkg != "" && (l.Tag == "ActivityManager" || l.Tag == "AndroidRuntime") && mentions(l.Msg, f.pkg):
		return true, l, banner
	}
	return false, l, banner
}

// mentions reports whether msg names pkg as a whole word: "dev.x" is not in "dev.xy",
// but it is in "dev.x/.Main", "dev.x:remote" and "(dev.x)".
func mentions(msg, pkg string) bool {
	for i := 0; ; {
		j := strings.Index(msg[i:], pkg)
		if j < 0 {
			return false
		}
		start, end := i+j, i+j+len(pkg)
		if (start == 0 || !pkgChar(msg[start-1])) && (end == len(msg) || !pkgChar(msg[end])) {
			return true
		}
		i = start + 1
	}
}

func pkgChar(c byte) bool {
	return c == '.' || c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
}

var levelColor = map[byte]string{'V': "\x1b[90m", 'D': "\x1b[36m", 'W': "\x1b[33m", 'E': "\x1b[31m", 'F': "\x1b[1;31m"}

func isTerminal(w io.Writer) bool {
	f, ok := w.(*os.File)
	if !ok {
		return false
	}
	st, err := f.Stat()
	return err == nil && st.Mode()&os.ModeCharDevice != 0
}

// logcatSince turns "5m back from the device's clock" into logcat's -T time format.
func logcatSince(deviceNow string, back time.Duration) (string, error) {
	t, err := time.Parse("01-02 15:04:05", strings.TrimSpace(deviceNow))
	if err != nil {
		return "", fmt.Errorf("device date %q: %w", deviceNow, err)
	}
	return t.Add(-back).Format("01-02 15:04:05.000"), nil
}

func (e *env) followLogs(ctx context.Context, d *adb.Device, o logOpts) error {
	args := []string{"logcat", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"}
	switch {
	case o.since > 0:
		now, err := d.MustShell(ctx, "date '+%m-%d %H:%M:%S'")
		if err != nil {
			return err
		}
		t, err := logcatSince(now, o.since)
		if err != nil {
			return err
		}
		args = append(args, "-T", t)
	case !o.dump:
		args = append(args, "-T", "1")
	}
	if o.dump {
		args = append(args, "-d")
	}
	pid := 0
	if o.pkg != "" {
		pid, _, _ = d.PID(ctx, o.pkg)
	}
	f := newLogFilter(o.pkg, o.tags, pid)
	color := !o.raw && isTerminal(e.stdout)

	var outMu sync.Mutex
	printBanner := func(b string) {
		if b == "" {
			return
		}
		outMu.Lock()
		defer outMu.Unlock()
		if color {
			b = "\x1b[1;35m" + b + "\x1b[0m"
		}
		fmt.Fprintln(e.stdout, b)
	}
	if o.pkg != "" && !o.dump {
		if pid == 0 {
			printBanner(fmt.Sprintf("── %s is not running: waiting for it ──", o.pkg))
		}
		go func() { // backup for restarts whose Start proc line we miss
			t := time.NewTicker(time.Second)
			defer t.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-t.C:
					if p, ok, err := d.PID(ctx, o.pkg); err == nil && ok {
						printBanner(f.setPID(p))
					}
				}
			}
		}()
	}

	cmd := d.Command(ctx, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = e.stderr
	e.logf("$ adb %s", strings.Join(args, " "))
	if err := cmd.Start(); err != nil {
		return err
	}
	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		s := strings.TrimRight(sc.Text(), "\r")
		keep, l, banner := f.line(s)
		printBanner(banner)
		if !keep {
			continue
		}
		outMu.Lock()
		if c, ok := levelColor[l.Level]; ok && color {
			fmt.Fprintln(e.stdout, c+s+"\x1b[0m")
		} else {
			fmt.Fprintln(e.stdout, s)
		}
		outMu.Unlock()
	}
	err = cmd.Wait()
	if ctx.Err() != nil {
		return nil // Ctrl-C
	}
	return err
}
