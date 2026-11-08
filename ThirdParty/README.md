# Fremdbibliotheken

## LAME 3.100 (MP3-Encoder)

- Quelle: https://lame.sourceforge.io – `lame-3.100.tar.gz`
  (SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`)
- Lizenz: GNU LGPL 2 (siehe `LAME-COPYING.txt`)
- Übernommen: nur der Encoder (`libmp3lame/*.c`, ohne `mpglib_interface.c`, ohne SSE/NASM-Code) und `include/lame.h`
- `libmp3lame/config.h` ist handgeschrieben (ersetzt `./configure`)
- `include/module.modulemap` macht die Bibliothek in Swift als `import LAME` verfügbar
