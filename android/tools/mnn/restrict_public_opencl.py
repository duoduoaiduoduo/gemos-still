"""Restrict the reviewed CI source copy to Android's public libOpenCL.so name.

No namespace APIs, absolute vendor paths, device settings, or system files are
used or modified. This is a loader policy change, not a numerical model change.
"""
from pathlib import Path
import subprocess
import sys

root=Path(sys.argv[1]).resolve()
expected="dbf9ba61b08106a3b43426e295d1a29cb41cc8fe"
actual=subprocess.check_output(["git","-C",str(root),"rev-parse","HEAD"],text=True).strip()
if actual!=expected:
    raise SystemExit("Refusing an unreviewed MNN source revision: "+actual)
path=root/"source/backend/opencl/core/runtime/OpenCLWrapper.cpp"
text=path.read_text()
start=text.index("#elif defined(__ANDROID__)\n")
end=text.index("#elif defined(__linux__)\n",start)
android='''#elif defined(__ANDROID__)
        // Gemos diagnostic build: public Android native library only.
        // No private driver aliases, absolute vendor paths, or namespace bypass.
        "libOpenCL.so",
'''
text=text[:start]+android+text[end:]
block=text[text.index("#elif defined(__ANDROID__)\n"):text.index("#elif defined(__linux__)\n")]
assert block.count('"libOpenCL.so"')==1
assert '/vendor/' not in block and 'libGLES' not in block and 'libmali' not in block
if len(sys.argv)>2 and sys.argv[2]=='--check':
    print("Verified public-library-only source-copy transformation; original source unchanged")
else:
    path.write_text(text)
    print("Restricted Android OpenCL loader to the public libOpenCL.so name")

