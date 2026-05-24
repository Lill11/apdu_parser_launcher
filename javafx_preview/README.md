# JavaFX Preview

This folder contains a separate JavaFX preview for the APDU Parser Launcher.

It does **not** replace the existing Swing launcher.

## What this preview is for

This version is meant to help you compare the feel of a JavaFX UI for the same workflow:

- import customer logs
- detect parser type
- run parser extraction
- inspect raw APDUs
- inspect enhanced analysis
- focus on `ALL`, `ES10`, `FETCH/TR`, and `LSI`

## Files

- [src/ApduQaWorkbenchFX.java](</C:/Users/junli/Documents/Codex/apdu_parser_launcher/javafx_preview/src/ApduQaWorkbenchFX.java>)
- [apdu-workbench.css](</C:/Users/junli/Documents/Codex/apdu_parser_launcher/javafx_preview/apdu-workbench.css>)
- [launch_fx_preview.bat](</C:/Users/junli/Documents/Codex/apdu_parser_launcher/javafx_preview/launch_fx_preview.bat>)

## How to run

This preview can now auto-detect a local BellSoft Liberica Full JDK that already includes JavaFX.

Recommended:

1. Install `BellSoft Liberica Full JDK` with JavaFX.
2. Run:

```bat
C:\Users\junli\Documents\Codex\apdu_parser_launcher\javafx_preview\launch_fx_preview.bat
```

Fallback option:

If you use a separate JavaFX SDK instead of Liberica Full, set `JAVAFX_LIB` to the SDK `lib` folder first.

```bat
set JAVAFX_LIB=C:\tools\javafx-sdk-24\lib
C:\Users\junli\Documents\Codex\apdu_parser_launcher\javafx_preview\launch_fx_preview.bat
```

## Notes

- The preview reuses the existing parser engine and analyzer.
- The old Swing launcher is untouched.
- This is a comparison build, so it is mainly focused on layout and workflow feel.
