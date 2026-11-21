import struct,sys,os
import numpy as np
from PIL import Image
def lz4(src,n):
    dst=bytearray(n); i=0;o=0;L=len(src)
    while i<L:
        t=src[i]; i+=1; l=t>>4
        if l==15:
            while True:
                b=src[i]; i+=1; l+=b
                if b!=255: break
        dst[o:o+l]=src[i:i+l]; i+=l; o+=l
        if i>=L: break
        off=src[i]|(src[i+1]<<8); i+=2; m=t&15
        if m==15:
            while True:
                b=src[i]; i+=1; m+=b
                if b!=255: break
        m+=4; s=o-off
        if off>=m: dst[o:o+m]=dst[s:s+m]
        else:
            pat=bytes(dst[s:o]); rep=(pat*(m//off+1))[:m]; dst[o:o+m]=rep
        o+=m
    return bytes(dst)
def load(p):
    d=open(p,'rb').read()
    assert d[:8]==b'TEXV0005' and d[9:17]==b'TEXI0001'
    fmt,flags,tw,th,iw,ih,unk=struct.unpack_from('<7i',d,18)
    tb=d[46:54]; o=55
    cnt=struct.unpack_from('<i',d,o)[0]; o+=4
    fi=-1
    if tb in(b'TEXB0003',b'TEXB0004'): fi=struct.unpack_from('<i',d,o)[0]; o+=4
    if tb==b'TEXB0004': o+=4
    mips=struct.unpack_from('<i',d,o)[0]; o+=4
    w,h,isl,dn,sz=struct.unpack_from('<5i',d,o); o+=20
    raw=d[o:o+sz]
    if isl: raw=lz4(raw,dn)
    info=dict(fmt=fmt,flags=flags,tex=(tw,th),img=(iw,ih),tb=tb.decode(),fi=fi,mips=mips,mip0=(w,h),lz4=isl,n=len(raw))
    if fi>=0 and fi!=-1 and raw[:4] in(b'\x89PNG',b'\xff\xd8\xff\xe0',b'\xff\xd8\xff\xe1'):
        import io; im=Image.open(io.BytesIO(raw)).convert('RGBA')
    elif fmt==0: im=Image.frombytes('RGBA',(w,h),raw)
    elif fmt==9: im=Image.frombytes('L',(w,h),raw).convert('RGBA')
    elif fmt==8: im=Image.frombytes('LA',(w,h),raw).convert('RGBA')
    else: im=None
    if im: im=im.crop((0,0,min(iw,im.width),min(ih,im.height)))
    return info,im
if __name__=='__main__':
    root=sys.argv[1]; out=sys.argv[2]; os.makedirs(out,exist_ok=True)
    for dp,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.tex'):
                info,im=load(os.path.join(dp,f)); print(f,info)
                if im: im.save(os.path.join(out,f[:-4]+'.png'))
