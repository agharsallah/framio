package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// capturesRoot is -o, else $FR_OUT, else <git top-level>/captures, else ~/Movies/fr.
func (e *env) capturesRoot(ctx context.Context) string {
	if e.out != "" {
		return e.out
	}
	if d := os.Getenv("FR_OUT"); d != "" {
		return d
	}
	if top, err := exec.CommandContext(ctx, "git", "rev-parse", "--show-toplevel").Output(); err == nil {
		return filepath.Join(strings.TrimSpace(string(top)), "captures")
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Movies", "fr")
}

// capturePath returns a free path captures/<date>/<HHMMSS>_<pkg>[_label]<ext>.
// It never returns an existing file: -2, -3… are added instead.
func (e *env) capturePath(ctx context.Context, now time.Time, pkg, label, ext string) (string, error) {
	dir := filepath.Join(e.capturesRoot(ctx), now.Format("2006-01-02"))
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return freePath(dir, captureName(now, pkg, label), ext), nil
}

func captureName(now time.Time, pkg, label string) string {
	name := now.Format("150405")
	if p := slug(pkg); p != "" {
		name += "_" + p
	}
	if l := slug(label); l != "" {
		name += "_" + l
	}
	return name
}

func freePath(dir, base, ext string) string {
	p := filepath.Join(dir, base+ext)
	for i := 2; exists(p); i++ {
		p = filepath.Join(dir, fmt.Sprintf("%s-%d%s", base, i, ext))
	}
	return p
}

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// slug keeps letters, digits, '.' and '-'; everything else becomes '-'.
func slug(s string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(strings.TrimSpace(s)) {
		ok := r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.'
		if ok {
			b.WriteRune(r)
			dash = false
		} else if !dash && b.Len() > 0 {
			b.WriteByte('-')
			dash = true
		}
	}
	return strings.TrimRight(b.String(), "-")
}

// writeAtomic writes data to path through a temp file and a rename.
func writeAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// display shortens path relative to the working directory when it is inside it.
func display(path string) string {
	wd, err := os.Getwd()
	if err != nil {
		return path
	}
	if rel, err := filepath.Rel(wd, path); err == nil && !strings.HasPrefix(rel, "..") {
		return rel
	}
	return path
}

// printPath prints a file fr created. In a terminal: a short path and details.
// Piped (scripts, $(fr shot)): only the absolute path, nothing to parse.
func (e *env) printPath(path, details string) {
	if !isTerminal(e.stdout) {
		abs, err := filepath.Abs(path)
		if err != nil {
			abs = path
		} else if strings.HasSuffix(path, "/") {
			abs += "/" // Abs drops it; keep folders recognisable
		}
		fmt.Fprintln(e.stdout, abs)
		return
	}
	if details != "" {
		fmt.Fprintf(e.stdout, "%s  (%s)\n", display(path), details)
	} else {
		fmt.Fprintln(e.stdout, display(path))
	}
}

// copyImage puts a PNG on the macOS clipboard, ready to paste.
func copyImage(ctx context.Context, path string) error {
	abs, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	script := fmt.Sprintf(`set the clipboard to (read (POSIX file %q) as «class PNGf»)`, abs)
	if out, err := exec.CommandContext(ctx, "osascript", "-e", script).CombinedOutput(); err != nil {
		return fmt.Errorf("copy to clipboard: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func openFile(ctx context.Context, path string) error {
	return exec.CommandContext(ctx, "open", path).Run()
}

func humanBytes(n int64) string {
	switch {
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%d KB", n>>10)
	}
	return fmt.Sprintf("%d B", n)
}

func ms(d time.Duration) string {
	if d < time.Second {
		return fmt.Sprintf("%d ms", d.Milliseconds())
	}
	return fmt.Sprintf("%.1f s", d.Seconds())
}
