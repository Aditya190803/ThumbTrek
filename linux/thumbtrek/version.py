"""Single-sourced version. Bump together with app/build.gradle.kts."""

__version__ = "0.5.0"
VERSION_CODE = 7

#: Platform tag appended to user-visible versions so the Linux port is
#: distinguishable from the Android/web releases carrying the same number.
#: Packaging versions (PKGBUILD/RPM/deb) stay plain numeric — hyphens are
#: illegal there — and keep reading __version__.
VARIANT = "Linux"
DISPLAY_VERSION = f"{__version__}-{VARIANT}"
