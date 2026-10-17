package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

func ints(args []string) ([]int, bool) {
	v := make([]int, len(args))
	for i, a := range args {
		n, err := strconv.Atoi(a)
		if err != nil {
			return nil, false
		}
		v[i] = n
	}
	return v, true
}

func cmdTap(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "tap")
	nth := fs.Int("nth", 0, "which match to tap when several match (1 = first)")
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
	if xy, ok := ints(pos); ok && len(xy) == 2 {
		_, err := d.MustShell(ctx, fmt.Sprintf("input tap %d %d", xy[0], xy[1]))
		return err
	}
	query := strings.Join(pos, " ")
	found, err := findOnScreen(ctx, d, query)
	if err != nil {
		return err
	}
	switch {
	case len(found) == 0:
		return fmt.Errorf("nothing on screen matches %q (fr find lists what is there)", query)
	case len(found) > 1 && *nth == 0 && (adb.MatchesExactly(found[1], query) || !adb.MatchesExactly(found[0], query)):
		// One exact match wins over partial ones; anything else is ambiguous.
		printNodes(e, found)
		return fmt.Errorf("%d elements match %q: pick one with --nth", len(found), query)
	}
	n := found[0]
	if *nth > 0 {
		if *nth > len(found) {
			return fmt.Errorf("--nth %d: only %d matches", *nth, len(found))
		}
		n = found[*nth-1]
	}
	c := n.Center()
	if _, err := d.MustShell(ctx, fmt.Sprintf("input tap %d %d", c.X, c.Y)); err != nil {
		return err
	}
	fmt.Fprintf(e.stdout, "tapped %q at %d,%d\n", n.Label(), c.X, c.Y)
	return nil
}

func cmdFind(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "find")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	var nodes []adb.Node
	if len(pos) > 0 {
		nodes, err = findOnScreen(ctx, d, strings.Join(pos, " "))
	} else {
		nodes, err = uiDump(ctx, d)
	}
	if err != nil {
		return err
	}
	if len(pos) == 0 {
		var named []adb.Node
		for _, n := range nodes {
			if n.Label() != "" && !n.Bounds.Empty() {
				named = append(named, n)
			}
		}
		nodes = named
	}
	if len(nodes) == 0 {
		return errors.New("no matching elements")
	}
	printNodes(e, nodes)
	return nil
}

func printNodes(e *env, nodes []adb.Node) {
	for i, n := range nodes {
		c := n.Center()
		cls := n.Class[strings.LastIndex(n.Class, ".")+1:]
		click := ""
		if n.Clickable {
			click = " clickable"
		}
		fmt.Fprintf(e.stdout, "%2d  %-32q  center %4d,%-4d  [%d,%d][%d,%d]  %s%s\n", i+1, n.Label(), c.X, c.Y,
			n.Bounds.Min.X, n.Bounds.Min.Y, n.Bounds.Max.X, n.Bounds.Max.Y, cls, click)
	}
}

// findOnScreen dumps the screen and matches query. A WebView fills its
// accessibility tree a moment after it loads, so no match gets a second look.
func findOnScreen(ctx context.Context, d *adb.Device, query string) ([]adb.Node, error) {
	for try := 0; ; try++ {
		nodes, err := uiDump(ctx, d)
		if err != nil {
			return nil, err
		}
		if found := adb.FindNodes(nodes, query); len(found) > 0 || try == 1 {
			return found, nil
		}
		time.Sleep(time.Second)
	}
}

// uiDump returns the on-screen elements. uiautomator fails now and then while
// the screen animates, so it gets one retry.
func uiDump(ctx context.Context, d *adb.Device) ([]adb.Node, error) {
	const remote = "/data/local/tmp/fr-ui.xml"
	var last string
	for try := 0; try < 2; try++ {
		out, _, err := d.Shell(ctx, "uiautomator dump "+remote)
		if err != nil {
			return nil, err
		}
		if strings.Contains(out, "dumped to") {
			data, err := d.ExecOut(ctx, "cat "+remote+"; rm -f "+remote)
			if err != nil {
				return nil, err
			}
			return adb.ParseUIDump(data)
		}
		last = strings.TrimSpace(out)
		time.Sleep(300 * time.Millisecond)
	}
	return nil, fmt.Errorf("uiautomator dump: %s", last)
}

func cmdSwipe(ctx context.Context, e *env, args []string) error {
	v, ok := ints(args)
	if !ok || (len(v) != 4 && len(v) != 5) {
		return errors.New("usage: fr swipe <x1> <y1> <x2> <y2> [ms]")
	}
	return e.input(ctx, "input swipe "+joinInts(v))
}

func cmdLong(ctx context.Context, e *env, args []string) error {
	v, ok := ints(args)
	if !ok || (len(v) != 2 && len(v) != 3) {
		return errors.New("usage: fr long <x> <y> [ms]")
	}
	duration := 1000
	if len(v) == 3 {
		duration = v[2]
	}
	return e.input(ctx, fmt.Sprintf("input swipe %d %d %d %d %d", v[0], v[1], v[0], v[1], duration))
}

func joinInts(v []int) string {
	s := make([]string, len(v))
	for i, n := range v {
		s[i] = strconv.Itoa(n)
	}
	return strings.Join(s, " ")
}

func cmdText(ctx context.Context, e *env, args []string) error {
	if len(args) == 0 {
		return errors.New("usage: fr text <text>")
	}
	return e.input(ctx, "input text "+adb.Quote(inputText(strings.Join(args, " "))))
}

// inputText escapes text for `input text`, which reads %s as a space.
func inputText(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, "%", `\%`), " ", "%s")
}

var keyNames = map[string]string{
	"home": "HOME", "back": "BACK", "menu": "MENU", "power": "POWER", "wakeup": "WAKEUP", "sleep": "SLEEP",
	"enter": "ENTER", "tab": "TAB", "esc": "ESCAPE", "del": "DEL", "backspace": "DEL",
	"up": "DPAD_UP", "down": "DPAD_DOWN", "left": "DPAD_LEFT", "right": "DPAD_RIGHT", "center": "DPAD_CENTER",
	"volup": "VOLUME_UP", "voldown": "VOLUME_DOWN", "mute": "VOLUME_MUTE",
	"play": "MEDIA_PLAY_PAUSE", "apps": "APP_SWITCH",
}

// keyCode turns "home", "KEYCODE_HOME", "HOME" or "3" into an input keyevent argument.
func keyCode(k string) string {
	if _, err := strconv.Atoi(k); err == nil {
		return k
	}
	if name, ok := keyNames[strings.ToLower(k)]; ok {
		return "KEYCODE_" + name
	}
	k = strings.ToUpper(k)
	if !strings.HasPrefix(k, "KEYCODE_") {
		k = "KEYCODE_" + k
	}
	return k
}

func cmdKey(ctx context.Context, e *env, args []string) error {
	if len(args) == 0 {
		return errors.New("usage: fr key home|back|menu|power|wakeup|sleep|enter|up|down|left|right|<KEYCODE>…")
	}
	var script []string
	for _, k := range args {
		script = append(script, "input keyevent "+keyCode(k))
	}
	return e.input(ctx, strings.Join(script, " && "))
}

func (e *env) input(ctx context.Context, script string) error {
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	out, err := d.MustShell(ctx, script)
	// `input` prints errors but exits 0 on old Android.
	if err == nil && (strings.Contains(out, "Error") || strings.Contains(out, "Exception") || strings.Contains(out, "Usage:")) {
		return fmt.Errorf("input: %s", strings.TrimSpace(out))
	}
	return err
}
