import sys,os
b=sys.stdin.buffer.read();i=0;found=False
while i+110<=len(b):
 h=b[i:i+110];assert h[:6] in (b'070701',b'070702');size=int(h[54:62],16);ns=int(h[94:102],16)
 n=b[i+110:i+110+ns-1].decode();start=(i+110+ns+3)&~3
 if n=='TRAILER!!!':break
 if n in ('./usr/sbin/nginx','usr/sbin/nginx'):
  assert not found;open('/tmp/nginx-real','wb').write(b[start:start+size]);os.chmod('/tmp/nginx-real',0o755);found=True
 i=(start+size+3)&~3
assert found