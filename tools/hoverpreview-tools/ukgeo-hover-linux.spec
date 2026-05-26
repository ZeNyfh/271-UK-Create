# -*- mode: python ; coding: utf-8 -*-

from pathlib import Path


ROOT = Path(SPECPATH).parent
UKGEO_TOOLS = ROOT / "ukgeo-tools"
HOVER_TOOLS = ROOT / "hoverpreview-tools"
SOURCE = HOVER_TOOLS / "src" / "hoverpreview_tools" / "launcher.py"

a = Analysis(
    [str(SOURCE)],
    pathex=[str(HOVER_TOOLS / "src"), str(UKGEO_TOOLS / "src")],
    binaries=[],
    datas=[],
    hiddenimports=["PIL._tkinter_finder"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="ukgeo-hover-linux",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
