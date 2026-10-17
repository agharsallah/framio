package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"image/png"
	"io"
	"strings"
	"time"

	"github.com/agharsallah/framio/tools/fr/internal/adb"
)

var pngMagic = []byte("\x89PNG\r\n\x1a\n")

func cmdShot(ctx context.Context, e *env, args []string) error {
	fs := newFlags(e, "shot")
	copyIt := fs.Bool("copy", false, "put the image on the clipboard")
	openIt := fs.Bool("open", false, "open the image")
	usePNG := fs.Bool("png", false, "let the device encode the PNG (screencap -p, slower)")
	burst := fs.Int("burst", 1, "number of screenshots")
	every := fs.Duration("every", time.Second, "interval between burst screenshots")
	pos, err := parseArgs(fs, args)
	if err != nil {
		return err
	}
	d, err := e.device(ctx)
	if err != nil {
		return err
	}
	label := strings.Join(pos, " ")
	for i := 0; i < *burst; i++ {
		start := time.Now()
		path, err := e.shot(ctx, d, label, *usePNG)
		if err != nil {
			return err
		}
		e.printPath(path, ms(time.Since(start)))
		if *copyIt && i == *burst-1 {
			if err := copyImage(ctx, path); err != nil {
				return err
			}
		}
		if *openIt && i == *burst-1 {
			if err := openFile(ctx, path); err != nil {
				return err
			}
		}
		if i < *burst-1 {
			select {
			case <-ctx.Done():
				return nil
			case <-time.After(time.Until(start.Add(*every))):
			}
		}
	}
	return nil
}

// shot saves one screenshot named after the foreground app and returns its path.
func (e *env) shot(ctx context.Context, d *adb.Device, label string, usePNG bool) (string, error) {
	now := time.Now()
	pkgc := make(chan string, 1)
	go func() {
		pkg, _, _ := d.Focus(ctx)
		pkgc <- pkg
	}()
	data, err := capturePNG(ctx, d, usePNG)
	if err != nil {
		return "", err
	}
	path, err := e.capturePath(ctx, now, <-pkgc, label, ".png")
	if err != nil {
		return "", err
	}
	return path, writeAtomic(path, data)
}

// capturePNG returns the screen as PNG bytes. By default the device sends raw
// pixels through gzip -1 and the PNG is encoded here: PNG encoding on a slow
// device CPU is the expensive part (0.50 s vs 1.33 s for screencap -p on the
// Veidoo F10). Any surprise falls back to screencap -p.
func capturePNG(ctx context.Context, d *adb.Device, devicePNG bool) ([]byte, error) {
	if !devicePNG {
		data, err := captureFast(ctx, d)
		if err == nil {
			return data, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if d.Verbose {
			fmt.Fprintf(d.Log, "  fast screenshot failed (%v), using screencap -p\n", err)
		}
	}
	data, err := d.ExecOut(ctx, "screencap -p")
	if err != nil {
		return nil, err
	}
	if !bytes.HasPrefix(data, pngMagic) {
		return nil, fmt.Errorf("screencap -p: not a PNG: %.80q", data)
	}
	return data, nil
}

func captureFast(ctx context.Context, d *adb.Device) ([]byte, error) {
	gz, err := d.ExecOut(ctx, "screencap | gzip -1")
	if err != nil {
		return nil, err
	}
	zr, err := gzip.NewReader(bytes.NewReader(gz))
	if err != nil {
		return nil, fmt.Errorf("gunzip: %w (%.60q)", err, gz)
	}
	raw, err := io.ReadAll(zr)
	if err != nil {
		return nil, fmt.Errorf("gunzip: %w", err)
	}
	img, err := adb.DecodeScreencap(raw)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	enc := png.Encoder{CompressionLevel: png.BestSpeed}
	if err := enc.Encode(&buf, img); err != nil {
		return nil, err
	}
	if buf.Len() == 0 {
		return nil, errors.New("empty PNG")
	}
	return buf.Bytes(), nil
}
