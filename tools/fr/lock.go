package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Several people or agents driving one device take turns through a lock
// directory ($FR_LOCK, e.g. /tmp/framio-frame.lock): mkdir is atomic, a lock
// untouched for 5 minutes is stale. The same protocol works from shell scripts
// (scripts/frame-lock.sh, examples/*/tools/frame.sh). Nothing goes inside the
// directory, or their rmdir would fail.
//
// Next to it, <lock>.owner (optional) says who holds it: name|pid|since|what.
// While fr holds the lock it touches both every 20 s, so only a crashed holder
// looks stale; a lock whose owner pid is gone is freed at once.

const (
	lockStale     = 5 * time.Minute
	lockHeartbeat = 20 * time.Second
)

var lockRetry = time.Second // tests shorten it

// lockingCommands take $FR_LOCK for their whole run. Long read-only streams
// (logs, mirror, rec) don't, and run takes it only around install and launch.
var lockingCommands = map[string]bool{
	"shot": true, "tap": true, "find": true, "swipe": true, "long": true, "text": true, "key": true,
	"install": true, "start": true, "stop": true, "restart": true, "clear": true, "uninstall": true,
	"screen": true, "sh": true, "bug": true, "reboot": true,
}

// lockOwner is one line of <lock>.owner.
type lockOwner struct {
	name  string
	pid   int
	since time.Time
	what  string
}

func (o lockOwner) String() string {
	return fmt.Sprintf("%s|%d|%d|%s", ownerField(o.name), o.pid, o.since.Unix(), ownerField(o.what))
}

// parseOwner reads name|pid|since|what. A line without a valid pid is not an
// owner: fr then falls back to the 5-minute rule rather than freeing the lock.
func parseOwner(line string) (lockOwner, bool) {
	f := strings.SplitN(strings.TrimRight(line, "\r\n"), "|", 4)
	if len(f) < 2 {
		return lockOwner{}, false
	}
	pid, err := strconv.Atoi(f[1])
	if err != nil || pid <= 0 {
		return lockOwner{}, false
	}
	o := lockOwner{name: f[0], pid: pid}
	if len(f) > 2 {
		if s, err := strconv.ParseInt(f[2], 10, 64); err == nil {
			o.since = time.Unix(s, 0)
		}
	}
	if len(f) > 3 {
		o.what = f[3]
	}
	return o, true
}

// describe is the "held by …" part of the waiting message.
func (o lockOwner) describe(now time.Time) string {
	s := "held by " + o.name
	if o.what != "" {
		s += " · " + o.what
	}
	if !o.since.IsZero() {
		s += fmt.Sprintf(" · %d s", int(now.Sub(o.since).Seconds()))
	}
	return s
}

// ownerField keeps a value on one line and out of the field separator.
func ownerField(s string) string {
	return strings.Map(func(r rune) rune {
		if r == '|' || r == '\n' || r == '\r' {
			return ' '
		}
		return r
	}, s)
}

// ownerName is who fr says it is in <lock>.owner.
func ownerName() string {
	for _, k := range []string{"FRAME_OWNER", "CLAUDE_SESSION_NAME"} {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	name := os.Getenv("USER")
	if u, err := user.Current(); err == nil {
		name = u.Username
	}
	return "fr:" + name
}

// lockWhat is the subcommand plus a short summary of its arguments, e.g.
// "install 06-pomodoro" or "shot". Typed text is left out: it may be a password.
func lockWhat(cmd string, args []string) string {
	parts := []string{cmd}
	if cmd != "text" {
		for _, a := range args {
			if strings.Contains(a, "/") && !strings.ContainsAny(a, " \t") {
				a = filepath.Base(a)
			}
			parts = append(parts, a)
		}
	}
	s := []rune(ownerField(strings.Join(parts, " ")))
	if len(s) > 60 {
		s = append(s[:59], '…')
	}
	return string(s)
}

// pidAlive reports whether a process exists (EPERM: it does, as another user).
func pidAlive(pid int) bool {
	err := syscall.Kill(pid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
}

// lockState is what a waiter sees of a lock held by someone else.
type lockState struct {
	dir      os.FileInfo   // the lock directory, to tell this hold of the lock from a later one
	age      time.Duration // since the lock directory was last touched
	line     string        // <lock>.owner as read, even a leftover; "" when there is none
	owner    lockOwner
	hasOwner bool // <lock>.owner exists, parses, and belongs to this hold of the lock
}

// readLock looks at the lock and its owner file. ok is false when the lock is
// gone: a missing lock is free (retry mkdir), never "ancient".
// An owner file older than the lock directory was left by an earlier holder (a
// bare rmdir doesn't remove it), so it says nothing about the current one.
func readLock(path string, now time.Time) (st lockState, ok bool) {
	dir, err := os.Lstat(path)
	if err != nil || !dir.IsDir() {
		return st, false
	}
	st.dir, st.age = dir, now.Sub(dir.ModTime())
	of, err := os.Lstat(path + ".owner")
	if err != nil {
		return st, true
	}
	b, err := os.ReadFile(path + ".owner")
	if err != nil {
		return st, true
	}
	st.line = string(b)
	if !of.ModTime().Before(dir.ModTime().Add(-2 * time.Second)) {
		st.owner, st.hasOwner = parseOwner(st.line)
	}
	return st, true
}

// sameLock reports whether two looks at the lock directory saw the same hold of
// it: same directory (a new mkdir is a new inode) and not touched since.
func sameLock(a, b os.FileInfo) bool {
	return a != nil && b != nil && os.SameFile(a, b) && a.ModTime().Equal(b.ModTime())
}

// reapReason says why a lock held by someone else may be freed now, or "" when
// it may not: its owner process is gone, or nobody touched it for 5 minutes.
func reapReason(st lockState, alive func(pid int) bool) string {
	if st.dir == nil || st.dir.ModTime().Unix() <= 0 {
		return "" // not held, or no usable mtime: retry mkdir, never "ancient"
	}
	if st.hasOwner && !alive(st.owner.pid) {
		return fmt.Sprintf("its holder is gone (%s)", st.owner)
	}
	if st.age > lockStale {
		return fmt.Sprintf("it was untouched for over %s", lockStale)
	}
	return ""
}

// reap frees a lock whose holder is gone. The caller then competes through
// mkdir like everyone else.
func (e *env) reap(path string) bool {
	st, ok := readLock(path, time.Now())
	if !ok {
		return false
	}
	why := reapReason(st, pidAlive)
	if why == "" || !freeLock(path, st) {
		return false
	}
	fmt.Fprintf(e.stderr, "freed the device lock %s: %s\n", path, why)
	return true
}

// freeLock removes the lock judged free in st, unless it changed hands since.
//
// Several waiters may judge the same dead lock at once, and the second must not
// remove the lock the first just freed and re-took. So fr reapers take turns
// through a second directory, <lock>.reap (mkdir again; a reaper that loses
// just waits for its next round), and the one inside re-reads the lock right
// before removing it: same directory (a new mkdir is a new inode), same mtime,
// same owner line, or it backs off. The owner file goes first, while the judged
// directory still blocks any new holder from writing one; then rmdir.
//
// Not atomic against reapers outside fr (scripts/frame-lock.sh, a manual
// rmdir): one of those freeing the lock and a new holder taking it in the few
// syscalls between the re-check and the rmdir can still lose that new lock.
// The protocol has no compare-and-delete, so that window is kept to a minimum.
func freeLock(path string, st lockState) bool {
	turn := path + ".reap"
	if err := os.Mkdir(turn, 0o755); err != nil {
		if t, err := os.Lstat(turn); err == nil && time.Since(t.ModTime()) > 30*time.Second {
			_ = os.Remove(turn) // a reaper died inside; compete again next round
		}
		return false
	}
	defer os.Remove(turn)
	again, ok := readLock(path, time.Now())
	if !ok || !sameLock(st.dir, again.dir) || again.line != st.line {
		return false
	}
	if st.line != "" {
		_ = os.Remove(path + ".owner")
	}
	return os.Remove(path) == nil
}

// lockOwnerPid is the pid in <lock>.owner, 0 when there is none.
func lockOwnerPid(path string) int {
	b, err := os.ReadFile(path + ".owner")
	if err != nil {
		return 0
	}
	o, _ := parseOwner(string(b))
	return o.pid
}

func ownedByUs(path string) bool { return lockOwnerPid(path) == os.Getpid() }

// touchLock keeps a lock we hold fresh. It never creates anything, and does
// nothing once the lock is gone or no longer ours.
func touchLock(path string, now time.Time) {
	if st, err := os.Stat(path); err != nil || !st.IsDir() || !ownedByUs(path) {
		return
	}
	_ = os.Chtimes(path, now, now)
	_ = os.Chtimes(path+".owner", now, now)
}

// lock waits for $FR_LOCK and returns the function that releases it. Without
// $FR_LOCK, or when this process already holds it, it does nothing. what says
// what fr is doing, for the owner file.
func (e *env) lock(ctx context.Context, what string) (func(), error) {
	path := os.Getenv("FR_LOCK")
	if path == "" || e.locked {
		return func() {}, nil
	}
	start := time.Now()
	for next := 30 * time.Second; ; {
		if err := os.Mkdir(path, 0o755); err == nil {
			break
		} else if !os.IsExist(err) {
			return nil, fmt.Errorf("lock %s: %w", path, err)
		}
		if e.reap(path) {
			continue
		}
		if time.Since(start) >= next {
			held := ""
			if st, ok := readLock(path, time.Now()); ok && st.hasOwner {
				held = ", " + st.owner.describe(time.Now())
			}
			fmt.Fprintf(e.stderr, "waiting for the device lock %s (%s%s)…\n", path, time.Since(start).Round(time.Second), held)
			next += 30 * time.Second
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(lockRetry):
		}
	}
	e.logf("lock %s taken after %s", path, time.Since(start).Round(time.Millisecond))
	me := lockOwner{name: ownerName(), pid: os.Getpid(), since: time.Now(), what: what}
	if err := os.WriteFile(path+".owner", []byte(me.String()+"\n"), 0o644); err != nil {
		e.logf("lock owner file: %v", err)
	}
	e.locked = true

	stop, done := make(chan struct{}), make(chan struct{})
	go func() {
		defer close(done)
		t := time.NewTicker(lockHeartbeat)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case now := <-t.C:
				touchLock(path, now)
			}
		}
	}()

	released := false
	return func() { // safe to call twice: only the first call releases the lock
		if released {
			return
		}
		released = true
		close(stop)
		<-done
		switch lockOwnerPid(path) {
		case os.Getpid():
			_ = os.Remove(path + ".owner")
			_ = os.Remove(path)
		case 0:
			_ = os.Remove(path)
		default: // someone freed our lock and took it meanwhile: it is theirs now
		}
		e.locked = false
	}, nil
}
