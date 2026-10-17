package adb

import (
	"encoding/binary"
	"encoding/xml"
	"errors"
	"fmt"
	"image"
	"regexp"
	"strconv"
	"strings"
)

// ParseDevices parses `adb devices -l`.
func ParseDevices(out string) []Attached {
	var all []Attached
	for _, line := range strings.Split(strings.ReplaceAll(out, "\r", ""), "\n") {
		f := strings.Fields(line)
		if len(f) < 2 || strings.HasPrefix(line, "List of devices") || strings.HasPrefix(line, "*") {
			continue
		}
		a := Attached{Serial: f[0], State: f[1]}
		for _, kv := range f[2:] {
			if m, ok := strings.CutPrefix(kv, "model:"); ok {
				a.Model = m
			}
		}
		all = append(all, a)
	}
	return all
}

// ParseRC splits Shell output into the text before the exit-code marker and the code.
// The marker can follow output that has no final newline ("text::fr-rc=0").
func ParseRC(out string) (string, int, bool) {
	out = strings.ReplaceAll(out, "\r", "")
	i := strings.LastIndex(out, rcMarker)
	if i < 0 {
		return out, -1, false
	}
	rest := strings.TrimSpace(out[i+len(rcMarker):])
	rc, err := strconv.Atoi(rest)
	if err != nil {
		return out, -1, false
	}
	return out[:i], rc, true
}

// ParsePS returns the PID of the line whose NAME column (the last) equals name.
// Works for Android 6 toolbox ps and toybox ps (PID is column 2 in both).
func ParsePS(out, name string) (int, bool) {
	for _, line := range strings.Split(strings.ReplaceAll(out, "\r", ""), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 || f[len(f)-1] != name {
			continue
		}
		if pid, err := strconv.Atoi(f[1]); err == nil {
			return pid, true
		}
	}
	return 0, false
}

var focusRE = regexp.MustCompile(`mCurrentFocus=Window\{\S+ (?:u\d+ )?([^\s}]+)`)

// ParseFocus reads `mCurrentFocus=Window{d207a49 u0 pkg/pkg.Activity}`.
// Windows without an activity (popups, the keyguard) return their title as pkg.
func ParseFocus(out string) (pkg, activity string) {
	m := focusRE.FindStringSubmatch(out)
	if m == nil {
		return "", ""
	}
	pkg, activity, _ = strings.Cut(m[1], "/")
	if strings.HasPrefix(activity, ".") {
		activity = pkg + activity
	}
	return pkg, activity
}

// DecodeScreencap decodes raw `screencap` output: a header (width, height,
// format as uint32 LE, plus a colorspace field since Android 8) and pixels.
// The header length is whatever is left over after width*height*4 bytes.
func DecodeScreencap(b []byte) (*image.NRGBA, error) {
	if len(b) < 12 {
		return nil, errors.New("screencap: short output")
	}
	w := int(binary.LittleEndian.Uint32(b[0:4]))
	h := int(binary.LittleEndian.Uint32(b[4:8]))
	format := binary.LittleEndian.Uint32(b[8:12])
	if w <= 0 || h <= 0 || w > 16384 || h > 16384 {
		return nil, fmt.Errorf("screencap: bad size %dx%d", w, h)
	}
	header := len(b) - w*h*4
	if header != 12 && header != 16 {
		return nil, fmt.Errorf("screencap: %d bytes for %dx%d, not 4 bytes per pixel", len(b), w, h)
	}
	pix := b[header:]
	switch format {
	case 1: // RGBA_8888
	case 2: // RGBX_8888
		for i := 3; i < len(pix); i += 4 {
			pix[i] = 0xff
		}
	case 5: // BGRA_8888
		for i := 0; i < len(pix); i += 4 {
			pix[i], pix[i+2] = pix[i+2], pix[i]
		}
	default:
		return nil, fmt.Errorf("screencap: pixel format %d not supported", format)
	}
	return &image.NRGBA{Pix: pix, Stride: w * 4, Rect: image.Rect(0, 0, w, h)}, nil
}

// Node is one element of a `uiautomator dump`.
type Node struct {
	Text, Desc, ID, Class, Package string
	Clickable                      bool
	Bounds                         image.Rectangle
}

// Center is where a tap on the node lands.
func (n Node) Center() image.Point {
	return image.Pt((n.Bounds.Min.X+n.Bounds.Max.X)/2, (n.Bounds.Min.Y+n.Bounds.Max.Y)/2)
}

// Label is the most readable name of the node.
func (n Node) Label() string {
	switch {
	case n.Text != "":
		return n.Text
	case n.Desc != "":
		return n.Desc
	}
	return n.ID
}

type xmlNode struct {
	Text      string    `xml:"text,attr"`
	Desc      string    `xml:"content-desc,attr"`
	ID        string    `xml:"resource-id,attr"`
	Class     string    `xml:"class,attr"`
	Package   string    `xml:"package,attr"`
	Clickable string    `xml:"clickable,attr"`
	Bounds    string    `xml:"bounds,attr"`
	Children  []xmlNode `xml:"node"`
}

var boundsRE = regexp.MustCompile(`^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$`)

// ParseUIDump returns every node of a `uiautomator dump`, in document order.
func ParseUIDump(data []byte) ([]Node, error) {
	var root struct {
		Nodes []xmlNode `xml:"node"`
	}
	if err := xml.Unmarshal(data, &root); err != nil {
		return nil, fmt.Errorf("ui dump: %w", err)
	}
	var all []Node
	var walk func([]xmlNode)
	walk = func(ns []xmlNode) {
		for _, x := range ns {
			n := Node{Text: x.Text, Desc: x.Desc, ID: x.ID, Class: x.Class, Package: x.Package, Clickable: x.Clickable == "true"}
			if m := boundsRE.FindStringSubmatch(x.Bounds); m != nil {
				v := make([]int, 4)
				for i := range v {
					v[i], _ = strconv.Atoi(m[i+1])
				}
				n.Bounds = image.Rect(v[0], v[1], v[2], v[3])
			}
			all = append(all, n)
			walk(x.Children)
		}
	}
	walk(root.Nodes)
	return all, nil
}

// FindNodes returns the nodes whose text, content-desc or resource-id matches
// query, ignoring case: exact matches first, then partial ones. Nodes with an
// empty area are skipped.
func FindNodes(nodes []Node, query string) []Node {
	q := strings.ToLower(strings.TrimSpace(query))
	if q == "" {
		return nil
	}
	var exact, partial []Node
	for _, n := range nodes {
		if n.Bounds.Empty() {
			continue
		}
		if MatchesExactly(n, q) {
			exact = append(exact, n)
			continue
		}
		for _, f := range []string{n.Text, n.Desc, n.ID} {
			if f != "" && strings.Contains(strings.ToLower(f), q) {
				partial = append(partial, n)
				break
			}
		}
	}
	return append(exact, partial...)
}

// MatchesExactly reports whether the node's text, content-desc or resource-id
// (with or without its "package:id/" prefix) equals query, ignoring case.
func MatchesExactly(n Node, query string) bool {
	q := strings.ToLower(strings.TrimSpace(query))
	for _, f := range []string{n.Text, n.Desc, n.ID} {
		f = strings.ToLower(f)
		if f != "" && (f == q || strings.HasSuffix(f, ":id/"+q)) {
			return true
		}
	}
	return false
}

// LogLine is one `logcat -v threadtime` line.
type LogLine struct {
	Time     string // MM-DD hh:mm:ss.mmm
	PID, TID int
	Level    byte
	Tag, Msg string
}

var logRE = regexp.MustCompile(`^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*: (.*)$`)

// ParseLogLine parses a threadtime line; ok is false for other lines (buffer headers…).
func ParseLogLine(s string) (LogLine, bool) {
	m := logRE.FindStringSubmatch(strings.TrimRight(s, "\r"))
	if m == nil {
		return LogLine{}, false
	}
	pid, _ := strconv.Atoi(m[2])
	tid, _ := strconv.Atoi(m[3])
	return LogLine{Time: m[1], PID: pid, TID: tid, Level: m[4][0], Tag: m[5], Msg: m[6]}, true
}

var startProcRE = regexp.MustCompile(`^Start proc (\d+):([^/\s]+)/`)

// ParseStartProc reads ActivityManager's "Start proc 1468:dev.framio.mawaqit/u0a27 for activity …".
func ParseStartProc(l LogLine) (pid int, pkg string, ok bool) {
	if l.Tag != "ActivityManager" {
		return 0, "", false
	}
	m := startProcRE.FindStringSubmatch(l.Msg)
	if m == nil {
		return 0, "", false
	}
	pid, _ = strconv.Atoi(m[1])
	return pid, m[2], true
}

// AmStart is the result of `am start -W`.
type AmStart struct {
	Activity  string
	TotalTime int // ms, -1 if not printed
	Warning   string
	Error     string
}

// ParseAmStart parses `am start -W` output.
func ParseAmStart(out string) AmStart {
	r := AmStart{TotalTime: -1}
	for _, line := range strings.Split(strings.ReplaceAll(out, "\r", ""), "\n") {
		k, v, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		v = strings.TrimSpace(v)
		switch k {
		case "Activity":
			r.Activity = v
		case "TotalTime":
			r.TotalTime, _ = strconv.Atoi(v)
		case "Warning":
			r.Warning = v
		case "Error":
			r.Error = v
		}
	}
	return r
}
