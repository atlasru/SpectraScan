package com.atlas.spectrascan

// Compatibility binding for ScannerActivity071's compact rect mapper.
// Kept package-local so the mapper can stay allocation-free.
internal var dh: Float = 0f
internal var valdh: Float
    get() = dh
    set(value) { dh = value }
