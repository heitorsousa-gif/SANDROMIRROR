#!/system/bin/sh
# Roda DENTRO do celular virtual (emulador), como root.
# Copia da memória do Hi-Link só os trechos que contêm o código do protocolo.
P=""
# "pidof" varia entre versões do Android; procura o processo direto em /proc.
for d in /proc/[0-9]*; do
  if [ "$(cat $d/cmdline 2>/dev/null | tr '\0' ' ' | tr -d ' ')" = "com.mirror.hsn" ]; then P=${d#/proc/}; break; fi
done
if [ -z "$P" ]; then
  echo "ERRO: abra o Hi-Link no celular virtual antes de rodar."
  exit 1
fi
D=/data/local/tmp/hl
rm -rf $D /data/local/tmp/hl.tgz
mkdir -p $D
cp /proc/$P/maps $D/maps.txt
grep -E '^[0-9a-f]+-[0-9a-f]+ r' /proc/$P/maps | while read range perms off dev inode path; do
  case "$path" in
    /system/*|/apex/*|/vendor/*|/product/*|/system_ext/*|*.ttf|*.oat|*.art|*.vdex) continue ;;
  esac
  s=${range%-*}
  e=${range#*-}
  S=$((0x$s))
  E=$((0x$e))
  N=$(( (E - S) / 4096 ))
  [ $N -gt 16384 ] && continue
  f=$D/$s.bin
  dd if=/proc/$P/mem of=$f bs=4096 skip=$((S / 4096)) count=$N 2>/dev/null
  if grep -q -a -e 'LTCV' -e 'com/mirror/hsn' $f 2>/dev/null; then
    echo "  encontrado: $s $path"
  else
    rm -f $f
  fi
done
cd /data/local/tmp && tar czf hl.tgz hl
ls -la /data/local/tmp/hl.tgz
