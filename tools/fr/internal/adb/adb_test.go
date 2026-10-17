package adb

import (
	"encoding/binary"
	"image"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Fixtures in testdata/ are real outputs captured from the Veidoo F10 (Android 6.0.1).
func fixture(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestParseDevices(t *testing.T) {
	got := ParseDevices(fixture(t, "devices.txt"))
	if len(got) != 1 || got[0].Serial != "68384696" || got[0].State != "device" || got[0].Model != "Frame" {
		t.Fatalf("got %+v", got)
	}
	got = ParseDevices("List of devices attached\n* daemon started successfully\nA\tunauthorized usb:1\nB\toffline\n\n")
	if len(got) != 2 || got[0].State != "unauthorized" || got[1].Serial != "B" {
		t.Fatalf("got %+v", got)
	}
}

func TestParseRC(t *testing.T) {
	tests := []struct {
		file, text string
		rc         int
	}{
		{"rc-false.txt", "hello\n", 1},
		{"rc-nonewline.txt", "nonewline", 0},
	}
	for _, tt := range tests {
		text, rc, ok := ParseRC(fixture(t, tt.file))
		if !ok || text != tt.text || rc != tt.rc {
			t.Errorf("%s: got %q %d %v", tt.file, text, rc, ok)
		}
	}
	if _, _, ok := ParseRC("error: device offline\n"); ok {
		t.Error("no marker must not be ok")
	}
	if _, _, ok := ParseRC("::fr-rc=\n"); ok {
		t.Error("empty code must not be ok")
	}
	// The marker can appear in the output itself; the last one wins.
	text, rc, _ := ParseRC("say ::fr-rc=7\n::fr-rc=0\r\n")
	if text != "say ::fr-rc=7\n" || rc != 0 {
		t.Errorf("got %q %d", text, rc)
	}
}

func TestParsePS(t *testing.T) {
	if pid, ok := ParsePS(fixture(t, "ps-launcher.txt"), "dev.framio.launcher"); !ok || pid != 615 {
		t.Errorf("launcher: %d %v", pid, ok)
	}
	if _, ok := ParsePS(fixture(t, "ps-none.txt"), "no.such.pkg"); ok {
		t.Error("header only must not match")
	}
	// toolbox ps filters by prefix: a :remote process must not count as the app.
	ps := "USER PID PPID VSIZE RSS WCHAN PC NAME\nu0_a25 900 180 1 1 ep_poll 0 S dev.framio.lab:remote\n"
	if _, ok := ParsePS(ps, "dev.framio.lab"); ok {
		t.Error("prefix must not match")
	}
	// toybox (Android 8+) layout.
	ps = "USER PID PPID VSZ RSS WCHAN ADDR S NAME\nu0_a25 4242 700 1 1 0 0 S dev.framio.lab\n"
	if pid, ok := ParsePS(ps, "dev.framio.lab"); !ok || pid != 4242 {
		t.Errorf("toybox: %d %v", pid, ok)
	}
}

func TestParseFocus(t *testing.T) {
	tests := []struct{ in, pkg, act string }{
		{fixture(t, "focus-mawaqit.txt"), "dev.framio.mawaqit", "dev.framio.mawaqit.MawaqitActivity"},
		{"  mCurrentFocus=Window{5ada98f u0 dev.framio.launcher/.LauncherActivity}", "dev.framio.launcher", "dev.framio.launcher.LauncherActivity"},
		{"  mCurrentFocus=Window{2f1c u0 PopupWindow:3a1b2c}", "PopupWindow:3a1b2c", ""},
		{"  mCurrentFocus=Window{1a2b StatusBar}", "StatusBar", ""},
		{"  mCurrentFocus=null", "", ""},
	}
	for _, tt := range tests {
		pkg, act := ParseFocus(tt.in)
		if pkg != tt.pkg || act != tt.act {
			t.Errorf("%q: got %q %q", tt.in, pkg, act)
		}
	}
}

func raw(w, h, format uint32, header int, pixel [4]byte) []byte {
	b := make([]byte, header, header+int(w*h)*4)
	binary.LittleEndian.PutUint32(b[0:], w)
	binary.LittleEndian.PutUint32(b[4:], h)
	binary.LittleEndian.PutUint32(b[8:], format)
	for i := 0; i < int(w*h); i++ {
		b = append(b, pixel[:]...)
	}
	return b
}

func TestDecodeScreencap(t *testing.T) {
	h := fixture(t, "screencap-header.bin")
	if w, ht, f := binary.LittleEndian.Uint32([]byte(h[0:])), binary.LittleEndian.Uint32([]byte(h[4:])), binary.LittleEndian.Uint32([]byte(h[8:])); w != 1280 || ht != 800 || f != 1 {
		t.Fatalf("frame header: %d %d %d", w, ht, f)
	}
	img, err := DecodeScreencap(raw(3, 2, 1, 12, [4]byte{10, 20, 30, 255}))
	if err != nil || img.Bounds() != image.Rect(0, 0, 3, 2) || img.Pix[0] != 10 || img.Pix[2] != 30 {
		t.Fatalf("12-byte header: %v %v", err, img)
	}
	img, err = DecodeScreencap(raw(3, 2, 1, 16, [4]byte{1, 2, 3, 255}))
	if err != nil || img.Bounds().Dx() != 3 {
		t.Fatalf("16-byte header: %v", err)
	}
	img, err = DecodeScreencap(raw(2, 2, 2, 12, [4]byte{1, 2, 3, 0}))
	if err != nil || img.Pix[3] != 0xff {
		t.Fatalf("RGBX alpha: %v", err)
	}
	img, err = DecodeScreencap(raw(2, 2, 5, 12, [4]byte{1, 2, 3, 255}))
	if err != nil || img.Pix[0] != 3 || img.Pix[2] != 1 {
		t.Fatalf("BGRA swap: %v %v", err, img.Pix[:4])
	}
	if _, err := DecodeScreencap(raw(2, 2, 4, 12, [4]byte{})); err == nil {
		t.Error("RGB_565 must fail")
	}
	if _, err := DecodeScreencap(raw(3, 2, 1, 12, [4]byte{})[:20]); err == nil {
		t.Error("truncated must fail")
	}
	if _, err := DecodeScreencap([]byte("/system/bin/sh: gzip: not found")); err == nil {
		t.Error("text must fail")
	}
}

func TestUIDump(t *testing.T) {
	nodes, err := ParseUIDump([]byte(fixture(t, "ui-mawaqit.xml")))
	if err != nil {
		t.Fatal(err)
	}
	got := FindNodes(nodes, "fajr")
	if len(got) != 2 || got[0].Bounds != image.Rect(790, 175, 876, 236) || got[0].Center() != image.Pt(833, 205) {
		t.Fatalf("both Fajr nodes, in document order: %+v", got)
	}
	got = FindNodes(nodes, "prayer")
	if len(got) != 1 || got[0].Text != "NEXT PRAYER" {
		t.Fatalf("partial match: %+v", got)
	}
	got = FindNodes(nodes, "next prayer")
	if len(got) != 1 || got[0].Bounds != image.Rect(790, 147, 920, 165) {
		t.Errorf("exact, case-insensitive: %+v", got)
	}
	xmlExact := `<hierarchy><node text="Lab tile" bounds="[0,0][9,9]"/><node text="Lab" bounds="[10,10][20,20]"/></hierarchy>`
	n2, _ := ParseUIDump([]byte(xmlExact))
	if got := FindNodes(n2, "lab"); len(got) != 2 || got[0].Text != "Lab" {
		t.Errorf("exact match must come before partial: %+v", got)
	}
	if FindNodes(nodes, "  ") != nil || len(FindNodes(nodes, "nothing like this")) != 0 {
		t.Error("empty or unknown query must not match")
	}
	xml := `<hierarchy><node text="" resource-id="dev.x:id/save_button" content-desc="" bounds="[0,0][10,10]"><node text="Hidden" bounds="[5,5][5,5]"/></node></hierarchy>`
	nodes, _ = ParseUIDump([]byte(xml))
	if got := FindNodes(nodes, "save_button"); len(got) != 1 || got[0].Label() != "dev.x:id/save_button" {
		t.Errorf("resource-id: %+v", got)
	}
	if got := FindNodes(nodes, "hidden"); len(got) != 0 {
		t.Errorf("zero-size node must be skipped: %+v", got)
	}
}

func TestLogLines(t *testing.T) {
	var starts []string
	for _, s := range strings.Split(strings.TrimSpace(fixture(t, "logcat-sample.txt")), "\n") {
		l, ok := ParseLogLine(s)
		if !ok {
			t.Fatalf("not parsed: %q", s)
		}
		if pid, pkg, ok := ParseStartProc(l); ok {
			starts = append(starts, pkg)
			if pkg == "dev.framio.mawaqit" && len(starts) == 2 && pid != 1468 {
				t.Errorf("mawaqit pid %d", pid)
			}
		}
	}
	if len(starts) == 0 || starts[0] != "dev.framio.launcher" {
		t.Errorf("starts %v", starts)
	}
	l, ok := ParseLogLine("10-18 00:53:12.345  5411  5430 W Framio Lab : with: colon\r")
	if !ok || l.PID != 5411 || l.Level != 'W' || l.Tag != "Framio Lab" || l.Msg != "with: colon" {
		t.Errorf("got %+v", l)
	}
	if _, ok := ParseLogLine("--------- beginning of crash"); ok {
		t.Error("buffer header must not parse")
	}
}

func TestParseAmStart(t *testing.T) {
	r := ParseAmStart(fixture(t, "am-start.txt"))
	if r.Activity != "dev.framio.launcher/.LauncherActivity" || r.TotalTime != 330 || r.Warning == "" || r.Error != "" {
		t.Errorf("got %+v", r)
	}
	r = ParseAmStart(fixture(t, "am-start-error.txt"))
	if r.Error == "" || r.TotalTime != -1 {
		t.Errorf("got %+v", r)
	}
}

func TestQuote(t *testing.T) {
	if got := Quote("it's a 'test'"); got != `'it'\''s a '\''test'\'''` {
		t.Errorf("got %s", got)
	}
}
