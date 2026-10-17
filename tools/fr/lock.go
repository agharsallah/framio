package main

import (
	"context"
	"fmt"
	"os"
	"time"
)

// Several people or agents driving one device take turns through a lock
// directory ($FR_LOCK, e.g. /tmp/framio-frame.lock): mkdir is atomic, a lock
// older than 5 minutes is stale. The same protocol works from shell scripts.

const lockStale = 5 * time.Minute

// lockingCommands take $FR_LOCK for their whole run. Long read-only streams
// (logs, mirror, rec) don't, and run takes it only around install and launch.
var lockingCommands = map[string]bool{
	"shot": true, "tap": true, "find": true, "swipe": true, "long": true, "text": true, "key": true,
	"install": true, "start": true, "stop": true, "restart": true, "clear": true, "uninstall": true,
	"screen": true, "sh": true, "bug": true, "reboot": true,
}

// lock waits for $FR_LOCK and returns the function that releases it. Without
// $FR_LOCK, or when this process already holds it, it does nothing.
func (e *env) lock(ctx context.Context) (func(), error) {
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
		if st, err := os.Stat(path); err == nil && time.Since(st.ModTime()) > lockStale {
			_ = os.Remove(path)
			continue
		}
		if time.Since(start) >= next {
			fmt.Fprintf(e.stderr, "waiting for the device lock %s (%s)…\n", path, time.Since(start).Round(time.Second))
			next += 30 * time.Second
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
	e.logf("lock %s taken after %s", path, time.Since(start).Round(time.Millisecond))
	e.locked = true
	released := false
	return func() { // safe to call twice: only the first call removes the lock
		if !released {
			released = true
			_ = os.Remove(path)
			e.locked = false
		}
	}, nil
}
