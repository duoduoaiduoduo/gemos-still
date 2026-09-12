from pathlib import Path
import numpy as np
from PIL import Image
size=2048;rng=np.random.default_rng(729)
noise=rng.normal(size=(size,size));spectrum=np.fft.rfft2(noise)
y=np.fft.fftfreq(size)[:,None];x=np.fft.rfftfreq(size)[None,:];r=x*x+y*y
coarse=np.fft.irfft2(spectrum*np.exp(-2*np.pi*np.pi*r*2.7**2),s=(size,size))
fine=np.fft.irfft2(spectrum*np.exp(-2*np.pi*np.pi*r*.75**2),s=(size,size))
coarse/=coarse.std();fine/=fine.std();height=np.clip(.5+.115*coarse+.022*fine,0,1)
path=Path(__file__).resolve().parents[1]/'baked'/'abs-height.png'
Image.fromarray(np.uint8(height*255)).save(path)
print(path)
