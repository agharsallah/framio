"""
Renders the models in models/ to WebP sprites with alpha for the app.

    cd tools/render3d
    uv run render.py                 # everything
    uv run render.py pip tuka        # only groups whose name starts with one of these
    uv run render.py --preview pip   # also writes a contact sheet to ./preview/

Each model is ray-marched on the GPU (headless OpenGL 4.1 through moderngl) at 3× the output size
and box-filtered down. All poses of one group (pip_idle, pip_blink, …) share one crop, so the app
can swap them in place for blinks and talking without anything jumping.
"""
from __future__ import annotations

import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image

from models import GROUPS
from sdf import Model

HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "app/src/main/assets/sprites"
PREVIEW = HERE / "preview"
SSAA = 3

PRELUDE = r"""
#version 330
uniform vec2 u_res;
uniform vec4 u_crop;      // window into the base view, in its NDC (-1..1)
uniform vec3 u_eye;
uniform vec3 u_target;
uniform float u_fov;
uniform float u_ground;   // 1 = soft contact shadow on the floor
uniform float u_groundY;
uniform float u_reach;   // how far the floor shadow may spread
uniform float u_scene;   // 1 = opaque scene: sky behind, haze with distance
uniform vec3 u_skyTop;
uniform vec3 u_skyBottom;
uniform float u_haze;
out vec4 frag;

vec3 srgb(vec3 c){ return pow(c, vec3(2.2)); }
float dot2(vec2 v){ return dot(v,v); }
float smin(float a,float b,float k){ if(k<=0.0) return min(a,b); float h=clamp(0.5+0.5*(b-a)/k,0.,1.); return mix(b,a,h)-k*h*(1.-h); }
float smax(float a,float b,float k){ return -smin(-a,-b,k); }

float sdEllipsoid(vec3 p, vec3 r){ float k0=length(p/r); float k1=length(p/(r*r)); return k0*(k0-1.0)/max(k1,1e-6); }
float sdCapsule(vec3 p, vec3 a, vec3 b, float r){ vec3 pa=p-a, ba=b-a; float h=clamp(dot(pa,ba)/dot(ba,ba),0.,1.); return length(pa-ba*h)-r; }
float sdRoundCone(vec3 p, vec3 a, vec3 b, float r1, float r2){
  vec3 ba=b-a; float l2=dot(ba,ba); float rr=r1-r2; float a2=l2-rr*rr; float il2=1.0/l2;
  vec3 pa=p-a; float y=dot(pa,ba); float z=y-l2; vec3 xv=pa*l2-ba*y; float x2=dot(xv,xv); float y2=y*y*l2; float z2=z*z*l2;
  float k=sign(rr)*rr*rr*x2;
  if(sign(z)*a2*z2>k) return sqrt(x2+z2)*il2-r2;
  if(sign(y)*a2*y2<k) return sqrt(x2+y2)*il2-r1;
  return (sqrt(x2*a2*il2)+y*rr)*il2-r1; }
float sdRoundBox(vec3 p, vec3 b, float r){ vec3 q=abs(p)-b+r; return length(max(q,0.))+min(max(q.x,max(q.y,q.z)),0.)-r; }
float sdCylinder(vec3 p, float h, float r, float rr){ vec2 d=vec2(length(p.xz)-r+rr, abs(p.y)-h+rr); return min(max(d.x,d.y),0.)+length(max(d,0.))-rr; }
float sdTorus(vec3 p, vec2 t){ vec2 q=vec2(length(p.xz)-t.x,p.y); return length(q)-t.y; }
float sdCappedTorus(vec3 p, vec2 sc, float ra, float rb){ p.x=abs(p.x); float k=(sc.y*p.x>sc.x*p.y)?dot(p.xy,sc):length(p.xy); return sqrt(max(dot(p,p)+ra*ra-2.0*ra*k,0.0))-rb; }
float extrude(float d2, float z, float h, float rr){ vec2 w=vec2(d2+rr, abs(z)-h+rr); return min(max(w.x,w.y),0.)+length(max(w,0.))-rr; }
float sdBox2(vec2 p, vec2 b){ vec2 d=abs(p)-b; return length(max(d,0.))+min(max(d.x,d.y),0.); }
float sdStar5(vec2 p, float r, float rf){
  const vec2 k1=vec2(0.809016994375,-0.587785252292); const vec2 k2=vec2(-k1.x,k1.y);
  p.x=abs(p.x); p-=2.0*max(dot(k1,p),0.0)*k1; p-=2.0*max(dot(k2,p),0.0)*k2; p.x=abs(p.x); p.y-=r;
  vec2 ba=rf*vec2(-k1.y,k1.x)-vec2(0,1); float h=clamp(dot(p,ba)/dot(ba,ba),0.0,r);
  return length(p-ba*h)*sign(p.y*ba.x-p.x*ba.y); }
float sdTriangle(vec2 p){ const float k=sqrt(3.0); p.y+=0.3; p.x=abs(p.x)-1.0; p.y=p.y+1.0/k; if(p.x+k*p.y>0.0) p=vec2(p.x-k*p.y,-k*p.x-p.y)/2.0; p.x-=clamp(p.x,-2.0,0.0); return -length(p)*sign(p.y)*0.9; }
float sdHeart(vec2 p){ p.x=abs(p.x); if(p.y+p.x>1.0) return sqrt(dot2(p-vec2(0.25,0.75)))-sqrt(2.0)/4.0; return sqrt(min(dot2(p-vec2(0.00,1.00)),dot2(p-0.5*max(p.x+p.y,0.0))))*sign(p.x-p.y); }
float sdHexagon(vec2 p, float r){ const vec3 k=vec3(-0.866025404,0.5,0.577350269); p=abs(p); p-=2.0*min(dot(k.xy,p),0.0)*k.xy; p-=vec2(clamp(p.x,-k.z*r,k.z*r),r); return length(p)*sign(p.y); }
float ndot(vec2 a, vec2 b){ return a.x*b.x-a.y*b.y; }
float sdRhombus(vec2 p, vec2 b){ p=abs(p); float h=clamp(ndot(b-2.0*p,b)/dot(b,b),-1.0,1.0); float d=length(p-0.5*b*vec2(1.0-h,1.0+h)); return d*sign(p.x*b.y+p.y*b.x-b.x*b.y); }
float sdEllipse2(vec2 p, vec2 r){ float k0=length(p/r); float k1=length(p/(r*r)); return k0*(k0-1.0)/max(k1,1e-6); }

// distance-only ops
void dU(inout float d,float di,float k){ d=smin(d,di,k); }
void dS(inout float d,float di,float k){ d=smax(d,-di,k); }
void dI(inout float d,float di,float k){ d=smax(d,di,k); }
// ops that also carry colour (c) and material (m = gloss, emit)
void cU(inout float d,inout vec3 c,inout vec2 m,float di,vec3 ci,vec2 mi,float k){
  if(k<=0.0){ if(di<d){d=di;c=ci;m=mi;} return; }
  float h=clamp(0.5+0.5*(d-di)/k,0.,1.); d=mix(d,di,h)-k*h*(1.-h); c=mix(c,ci,h); m=mix(m,mi,h); }
void cS(inout float d,inout vec3 c,inout vec2 m,float di,vec3 ci,float k){
  if(k<=0.0){ if(-di>d){d=-di;c=ci;} return; }
  float h=clamp(0.5-0.5*(d+di)/k,0.,1.); d=mix(d,-di,h)+k*h*(1.-h); c=mix(c,ci,h); }
void cI(inout float d,float di,float k){ d=smax(d,di,k); }
void cP(inout vec3 c,inout vec2 m,float di,vec3 ci,float soft,float g){
  float w=1.0-smoothstep(-soft,soft,di); c=mix(c,ci,w); if(g>=0.0) m.x=mix(m.x,g,w); }

// hash noise for a faint hand-made clay surface
float hash(vec3 p){ p=fract(p*0.3183099+0.1); p*=17.0; return fract(p.x*p.y*p.z*(p.x+p.y+p.z)); }
float vnoise(vec3 x){ vec3 i=floor(x); vec3 f=fract(x); f=f*f*(3.0-2.0*f);
  return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z); }
"""

MAIN = r"""
vec3 calcNormal(vec3 p){ const float h=0.0007; const vec2 k=vec2(1,-1);
  return normalize(k.xyy*mapD(p+k.xyy*h)+k.yyx*mapD(p+k.yyx*h)+k.yxy*mapD(p+k.yxy*h)+k.xxx*mapD(p+k.xxx*h)); }
float softShadow(vec3 ro, vec3 rd, float mint, float maxt, float w){
  float res=1.0; float t=mint;
  for(int i=0;i<160 && t<maxt;i++){ float h=mapD(ro+t*rd); res=min(res,h/(w*t)); t+=clamp(h,0.004,0.25); if(res<-1.0) break; }
  res=max(res,-1.0); return 0.25*(1.0+res)*(1.0+res)*(2.0-res); }
float calcAO(vec3 pos, vec3 nor){ float occ=0.0; float sca=1.0;
  for(int i=0;i<7;i++){ float h=0.012+0.2*float(i)/6.0; float d=mapD(pos+h*nor); occ+=(h-d)*sca; sca*=0.85; }
  return clamp(1.0-1.6*occ,0.0,1.0); }

const vec3 LIGHT = normalize(vec3(-0.45,0.8,0.95));

void main(){
  vec2 uv = gl_FragCoord.xy/u_res;
  vec2 ndc = mix(u_crop.xy, u_crop.zw, uv);
  vec3 ww=normalize(u_target-u_eye); vec3 uu=normalize(cross(ww,vec3(0,1,0))); vec3 vv=cross(uu,ww);
  float f=1.0/tan(radians(u_fov)*0.5);
  vec3 rd=normalize(ndc.x*uu+ndc.y*vv+f*ww);
  vec3 ro=u_eye;

  float t=0.0; bool hit=false;
  for(int i=0;i<320;i++){ float h=mapD(ro+rd*t); if(abs(h)<0.00025*t){hit=true;break;} t+=h*0.9; if(t>80.0) break; }

  if(hit){
    vec3 pos=ro+rd*t;
    vec3 alb; vec2 mat; mapC(pos,alb,mat);
    vec3 n=calcNormal(pos);
    // faint thumbprint texture: clay, not plastic
    vec3 q=pos*26.0; float e=0.02;
    vec3 g=vec3(vnoise(q+vec3(e,0,0))-vnoise(q-vec3(e,0,0)), vnoise(q+vec3(0,e,0))-vnoise(q-vec3(0,e,0)), vnoise(q+vec3(0,0,e))-vnoise(q-vec3(0,0,e)))/(2.0*e);
    n=normalize(n+g*0.0035*(1.0-mat.x));
    float gloss=mat.x, emit=mat.y;
    float ndl=dot(n,LIGHT);
    float dif=clamp((ndl+0.35)/1.35,0.0,1.0);
    float sha=mix(0.35,1.0,softShadow(pos+n*0.003,LIGHT,0.01,6.0,0.3));
    float ao=calcAO(pos,n);
    vec3 hal=normalize(LIGHT-rd);
    float spe=pow(clamp(dot(n,hal),0.0,1.0),mix(10.0,110.0,gloss))*mix(0.035,0.75,gloss)*clamp(ndl+0.2,0.,1.)*sha;
    float fre=pow(clamp(1.0+dot(n,rd),0.0,1.0),2.2);
    vec3 lin=vec3(0.0);
    lin+=dif*sha*vec3(1.25,1.14,1.0)*1.12;                      // warm window light
    lin+=(0.6+0.4*n.y)*vec3(0.60,0.65,0.80)*0.7*ao;         // soft sky
    lin+=clamp(0.5-0.5*n.y,0.0,1.0)*vec3(0.62,0.48,0.42)*0.35*ao; // warm floor bounce
    lin+=fre*vec3(1.0,0.96,0.98)*0.28*ao;                      // gentle rim
    vec3 col=alb*lin;
    col+=alb*alb*0.22*(1.0-dif*sha)*ao;                         // subsurface-ish warmth in shadow
    col+=spe*vec3(1.0,0.97,0.92);
    col=mix(col,alb*1.05+spe,emit);
    col=col/(1.0+col*0.3)*1.12;                                    // soft shoulder, never clips to glare
    col=pow(clamp(col,0.0,1.0),vec3(1.0/2.2));
    if(u_scene>0.5){
      // aerial haze: far things fade into the sky, and the whole scene stays light behind the content
      float fog=1.0-exp(-max(t-14.0,0.0)*0.011);
      col=mix(col,u_skyBottom,fog);
      col=mix(col,vec3(0.97,0.95,0.91),u_haze);
    }
    frag=vec4(col,1.0);
    return;
  }

  if(u_scene>0.5){
    float k=clamp(rd.y*2.2+0.35,0.0,1.0);
    vec3 sky=mix(u_skyBottom,u_skyTop,k);
    sky=mix(sky,vec3(0.97,0.95,0.91),u_haze*0.6);
    frag=vec4(sky,1.0);
    return;
  }

  if(u_ground>0.5 && rd.y<0.0){
    float tg=(u_groundY-ro.y)/rd.y;
    vec3 pos=ro+rd*tg;
    float sha=softShadow(pos+vec3(0,0.001,0),LIGHT,0.02,6.0,0.35);
    float near=clamp(mapD(pos)/0.6,0.0,1.0);
    float a=(1.0-sha)*0.16+(1.0-near)*(1.0-near)*0.34;
    a*=smoothstep(1.0,0.45,length(pos.xz-u_target.xz)/u_reach);   // shadows fade out: a sprite, not a scene
    a=clamp(a,0.0,0.42);
    vec3 tint=vec3(0.22,0.16,0.26);
    frag=vec4(tint*a,a);   // premultiplied
    return;
  }
  frag=vec4(0.0);
}
"""

VERT = """
#version 330
in vec2 pos;
void main(){ gl_Position=vec4(pos,0.0,1.0); }
"""


class Renderer:
    def __init__(self):
        self.ctx = moderngl.create_standalone_context(require=330)
        self.vbo = self.ctx.buffer(np.array([-1, -1, 3, -1, -1, 3], dtype="f4").tobytes())
        self.programs: dict[str, moderngl.Program] = {}

    def _program(self, model: Model) -> moderngl.Program:
        src = PRELUDE + model.glsl() + MAIN
        prog = self.programs.get(src)
        if prog is None:
            prog = self.ctx.program(vertex_shader=VERT, fragment_shader=src)
            self.programs[src] = prog
        return prog

    def raw(self, model: Model, w: int, h: int, crop=(-1.0, -1.0, 1.0, 1.0)) -> np.ndarray:
        """Premultiplied RGBA float image, top row first."""
        prog = self._program(model)
        yaw, pitch = np.radians(model.yaw), np.radians(model.pitch)
        tgt = np.array(model.target, dtype=float)
        eye = tgt + model.distance * np.array([np.sin(yaw) * np.cos(pitch), np.sin(pitch), np.cos(yaw) * np.cos(pitch)])
        for name, val in (("u_res", (w, h)), ("u_crop", crop), ("u_eye", tuple(eye)), ("u_target", tuple(tgt)),
                          ("u_fov", model.fov), ("u_ground", 1.0 if model.ground else 0.0), ("u_groundY", model.ground_y), ("u_reach", model.reach),
                          ("u_scene", 1.0 if model.scene else 0.0), ("u_skyTop", hexf(model.sky[0])),
                          ("u_skyBottom", hexf(model.sky[1])), ("u_haze", model.haze)):
            if name in prog:
                prog[name].value = val
        fbo = self.ctx.simple_framebuffer((w, h), components=4)
        fbo.use()
        fbo.clear(0, 0, 0, 0)
        vao = self.ctx.simple_vertex_array(prog, self.vbo, "pos")
        vao.render(moderngl.TRIANGLES)
        data = np.frombuffer(fbo.read(components=4), dtype=np.uint8).reshape(h, w, 4)[::-1].astype(np.float32) / 255
        vao.release()
        fbo.release()
        return data

    def tiled(self, model: Model, w: int, h: int, crop, tiles=4) -> np.ndarray:
        """Big scenes in tiles, so no single draw call runs long enough for the GPU watchdog."""
        x0, y0, x1, y1 = crop
        out = np.zeros((h, w, 4), np.float32)
        tw, th = w // tiles, h // tiles
        for ty in range(tiles):
            for tx in range(tiles):
                c = (x0 + (x1 - x0) * tx / tiles, y1 - (y1 - y0) * (ty + 1) / tiles,
                     x0 + (x1 - x0) * (tx + 1) / tiles, y1 - (y1 - y0) * ty / tiles)
                out[ty * th:(ty + 1) * th, tx * tw:(tx + 1) * tw] = self.raw(model, tw, th, c)
        return out

    def bbox(self, model: Model, n=320) -> tuple[float, float, float, float]:
        """Extent of everything visible (shadow included) in the base view's NDC."""
        img = self.raw(model, n, n)
        ys, xs = np.nonzero(img[..., 3] > 0.02)
        if len(xs) == 0:
            raise ValueError(f"{model.name}: nothing visible")
        to_ndc = lambda v: v / n * 2 - 1
        x0, x1 = to_ndc(xs.min()), to_ndc(xs.max() + 1)
        y0, y1 = -to_ndc(ys.max() + 1), -to_ndc(ys.min())
        if xs.min() == 0 or ys.min() == 0 or xs.max() == n - 1 or ys.max() == n - 1:
            print(f"  ! {model.name} touches the edge of the base view: raise distance", file=sys.stderr)
        return x0, y0, x1, y1

    def image(self, model: Model, size: tuple[int, int], crop) -> Image.Image:
        w, h = size
        big = self.tiled(model, w * SSAA, h * SSAA, crop) if w * h > 400_000 else self.raw(model, w * SSAA, h * SSAA, crop)
        small = big.reshape(h, SSAA, w, SSAA, 4).mean(axis=(1, 3))
        a = small[..., 3:4]
        rgb = np.where(a > 1e-4, small[..., :3] / np.maximum(a, 1e-4), 0)
        out = np.concatenate([rgb, a], axis=-1)
        return Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def hexf(h):
    """sRGB hex → the display-space colour the shader mixes in at the end."""
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def group_crop(boxes, size, pad=0.04):
    """One crop for all poses: the union box, padded, grown to the output aspect ratio."""
    x0 = min(b[0] for b in boxes) - pad
    y0 = min(b[1] for b in boxes) - pad
    x1 = max(b[2] for b in boxes) + pad
    y1 = max(b[3] for b in boxes) + pad
    aspect = size[0] / size[1]
    w, h = x1 - x0, y1 - y0
    if w / h < aspect:
        grow = h * aspect - w
        x0, x1 = x0 - grow / 2, x1 + grow / 2
    else:
        grow = w / aspect - h
        y0, y1 = y0 - grow / 2, y1 + grow / 2
    return x0, y0, x1, y1


def main(argv):
    preview = "--preview" in argv
    names = [a for a in argv if not a.startswith("--")]
    OUT.mkdir(parents=True, exist_ok=True)
    r = Renderer()
    sheet = []
    for group in GROUPS:
        if names and not any(group.name.startswith(n) for n in names):
            continue
        models = group.build()
        if models[0].scene:  # full frame: the base view stretched to the output's aspect ratio
            a = group.size[0] / group.size[1]
            crop = (-a, -1.0, a, 1.0)
        else:
            crop = group_crop([r.bbox(m) for m in models], group.size)
        for m in models:
            img = r.image(m, group.size, crop)
            path = OUT / f"{m.name}.webp"
            if m.scene:
                img.convert("RGB").save(path, "WEBP", quality=88, method=6)
            else:
                img.save(path, "WEBP", quality=92, method=6, exact=False)
            sheet.append(img)
            print(f"{path.relative_to(OUT.parent.parent.parent.parent.parent)}  {img.size[0]}×{img.size[1]}  {path.stat().st_size // 1024} KB")
    if preview and sheet:
        PREVIEW.mkdir(exist_ok=True)
        cell = 256
        cols = min(6, len(sheet))
        rows = (len(sheet) + cols - 1) // cols
        bg = Image.new("RGBA", (cols * cell, rows * cell), (247, 240, 228, 255))
        for i, im in enumerate(sheet):
            t = im.copy()
            t.thumbnail((cell, cell))
            bg.alpha_composite(t, ((i % cols) * cell + (cell - t.width) // 2, (i // cols) * cell + (cell - t.height) // 2))
        name = "_".join(names) or "all"
        bg.convert("RGB").save(PREVIEW / f"{name}.png")
        print(f"preview → {PREVIEW / (name + '.png')}")


if __name__ == "__main__":
    main(sys.argv[1:])
