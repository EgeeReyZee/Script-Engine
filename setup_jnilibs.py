"""
setup_jnilibs.py — Run ONCE before building to copy OpenCV .so into jniLibs.

Usage (from project root in PowerShell or cmd):
    python setup_jnilibs.py

This copies libopencv_java4.so into app/src/main/jniLibs/{abi}/
so the APK packages it and System.loadLibrary("opencv_java4") works at runtime.
"""
import os, shutil, sys

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

OPENCV_BASE = os.path.join(PROJECT_ROOT,
    "app", "src", "main", "jni", "OpenCV-android-sdk", "sdk", "native", "libs")

JNILIBS = os.path.join(PROJECT_ROOT, "app", "src", "main", "jniLibs")

# Copy from whichever ABI folders exist in OpenCV SDK
FILES = ["libopencv_java4.so"]

if not os.path.exists(OPENCV_BASE):
    print(f"ERROR: OpenCV SDK not found at:\n  {OPENCV_BASE}")
    print("Download from https://opencv.org/releases/ and extract to app/src/main/jni/OpenCV-android-sdk/")
    sys.exit(1)

copied = 0
for abi in os.listdir(OPENCV_BASE):
    src_dir = os.path.join(OPENCV_BASE, abi)
    if not os.path.isdir(src_dir):
        continue
    dst_dir = os.path.join(JNILIBS, abi)
    os.makedirs(dst_dir, exist_ok=True)
    for f in FILES:
        src = os.path.join(src_dir, f)
        dst = os.path.join(dst_dir, f)
        if os.path.exists(src):
            shutil.copy2(src, dst)
            size = os.path.getsize(dst) // 1024
            print(f"  Copied {abi}/{f}  ({size} KB)")
            copied += 1
        else:
            print(f"  SKIP {abi}/{f} (not found)")

if copied == 0:
    print("ERROR: No files were copied. Check OpenCV SDK path.")
    sys.exit(1)

print(f"\nDone — {copied} file(s) copied.")
print("Now rebuild the project in Android Studio (Build → Rebuild Project).")
