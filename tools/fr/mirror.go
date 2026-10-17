package main

import (
	"context"
	"fmt"
	"strings"
	"time"
)

func cmdMirror(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "mirror")
	rec := fs.Bool("rec", false, "also record while mirroring")
	top := fs.Bool("top", false, "keep the window above others")
	off := fs.Bool("off", false, "turn the device screen off while mirroring")
	size := fs.Int("size", 0, "limit the longer side to N pixels (0 = native)")
	if _, err := parseArgs(fs, args); err != nil {
		return err
	}
	if err := needScrcpy(); err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	model, _ := d.Prop(ctx, "ro.product.model")
	o := &recOpts{size: *size}
	var mkv, mp4 string
	if *rec {
		o.fps, o.bitrate = 30, "4M"
		pkg, _, _ := d.Focus(ctx)
		if mp4, err = e.capturePath(ctx, time.Now(), pkg, "mirror", ".mp4"); err != nil {
			return err
		}
		mkv = strings.TrimSuffix(mp4, ".mp4") + ".mkv"
	}
	args = append(scrcpyArgs(d, o, mkv, true),
		"--stay-awake", "--show-touches", "--push-target=/sdcard/Download/",
		"--window-title="+strings.TrimSpace(model+" "+d.Serial))
	if *top {
		args = append(args, "--always-on-top")
	}
	if *off {
		args = append(args, "--turn-screen-off")
	}
	fmt.Fprintln(e.stderr, "mirror: click = tap, right-click = Back, middle-click = Home, drop an APK = install, drop a file = push to /sdcard/Download. Close the window or Ctrl-C to quit.")
	if err := e.runScrcpy(ctx, d, args, false); err != nil {
		return err
	}
	if *rec {
		return e.finishRecording(ctx, mkv, mp4, false)
	}
	return nil
}
