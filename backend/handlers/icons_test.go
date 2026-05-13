package handlers

import "testing"

func TestSniffIconMime(t *testing.T) {
	cases := []struct {
		name    string
		data    []byte
		wantMT  string
		wantErr bool
	}{
		{
			name:   "PNG magic bytes",
			data:   append(pngMagic, 0x00, 0x00),
			wantMT: pngMimeType,
		},
		{
			name:   "SVG with xml declaration",
			data:   []byte(`<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>`),
			wantMT: svgMimeType,
		},
		{
			name:   "SVG without declaration",
			data:   []byte(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"/>`),
			wantMT: svgMimeType,
		},
		{
			name:   "SVG with leading whitespace + DOCTYPE",
			data:   []byte(`<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "..."><SVG/>`),
			wantMT: svgMimeType,
		},
		{
			name:    "empty",
			data:    []byte{},
			wantErr: true,
		},
		{
			name:    "JPEG magic — not allowed",
			data:    []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10},
			wantErr: true,
		},
		{
			name:    "Plain HTML",
			data:    []byte(`<html><body>hi</body></html>`),
			wantErr: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := sniffIconMime(tc.data)
			if (err != nil) != tc.wantErr {
				t.Fatalf("err = %v, wantErr=%v", err, tc.wantErr)
			}
			if !tc.wantErr && got != tc.wantMT {
				t.Errorf("mime = %q, want %q", got, tc.wantMT)
			}
		})
	}
}
