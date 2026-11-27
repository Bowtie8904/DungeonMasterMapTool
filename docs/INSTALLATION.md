# Windows graphics troubleshooting

The application defaults JavaFX's texture-pool ceiling to **1 GiB**, rather than 512 MiB,
while retaining hardware acceleration. This memory is allocated on demand, not reserved at
startup, and is separate from the Java heap. Full-screen canvas layers and their clipping/blending
buffers in two windows can exceed the previous ceiling even without a map loaded.
An explicit `-Dprism.maxvram=...` JVM option overrides the application default.

JavaFX Canvas backing textures use the highest recommended scale across **all connected screens**,
rounded up. A 1080p player monitor at 100% can therefore still have 3840 x 2160 backing textures
when another screen requires a 2x backing scale. The ten player canvases alone then account for
about 316 MiB, before the DM window, clipping and blending buffers. Hidden canvases can retain
their allocated textures; hiding a layer is not a texture-memory release. Do not disable HiDPI
scaling globally as a memory workaround: that also changes the DM UI and display calibration.

**For an older export**, close the app and add `java-options=-Dprism.maxvram=1g` under
`[JavaOptions]` in `app\DungeonMasterMapTool.cfg`, then run the normal EXE.
Remove any `java-options=-Dprism.order=sw` line from that configuration to keep GPU acceleration.

If opening Player View leaves a black screen, freezes rendering in both windows, or logs
`Texture must be non-null` / `D3DTexture` exceptions, close the application completely and run
**`DungeonMasterMapTool-Compatibility.exe`** instead. Both EXEs are included in the Windows ZIP
and local exports from `packaging\build-exe.bat`. Keep the EXE in the extracted application
folder; it shares the normal EXE's bundled Java, maps and settings.

The compatibility launcher selects JavaFX software rendering before startup, bypassing Direct3D.
It may be slower, especially with animated effects on large screens. It does not change saved
settings or maps. To return to hardware acceleration, close the app and run `DungeonMasterMapTool.exe`.
This is a workaround for graphics-pipeline failures, not a guarantee against all black-screen causes.

**Software-rendering fallback for an older EXE export:** No command line or Java installation is needed:

1. Close the application completely.
2. Open `app\DungeonMasterMapTool.cfg` inside the extracted application folder in Notepad.
3. Under the existing `[JavaOptions]` heading, add a separate line `java-options=-Dprism.order=sw`.
   Keep the other lines unchanged, then save the file.
4. Start `DungeonMasterMapTool.exe` normally. Remove that line and restart to undo the workaround.

Re-extracting or replacing the export may overwrite this manual configuration change.
