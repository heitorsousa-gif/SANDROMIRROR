package br.heitor.carlink;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Protocolo da central XM7706 ("Mirroring Suite"), descoberto no código do Hi-Link.
 *
 * Mensagem:
 *   [0..3]   tamanho total em little-endian (inclui estes 4 bytes)
 *   [4..7]   comando: 4 letras ASCII de trás para frente ("SYNC" vira "CNYS")
 *   [8..11]  seq: (base aleatória & 0xFFFF0000) | 16 bits aleatórios
 *   [12..15] resposta: seq da mensagem que está sendo respondida
 *   [16..19] chave de embaralhamento (em texto aberto)
 *   [20..]   campos do comando
 * Os bytes 4..63 são embaralhados (XOR) com a chave, depois a chave é gravada em 16..19.
 * Mensagens com menos de 64 bytes são completadas com bytes aleatórios até 64.
 * Batimento: 4 bytes zerados, que o celular devolve iguais.
 */
final class Proto {

    static final String SYNC = "SYNC";
    static final String STAR = "STAR";
    static final String VDAT = "VDAT";
    static final String LIVE = "LIVE";
    static final String VCTL = "VCTL";
    static final String SLCK = "SLCK";

    private static final Random RNG = new SecureRandom();
    private static final int BASE = new Random().nextInt();

    /** Mensagem recebida já desembaralhada. */
    static final class Msg {
        String cmd;
        int seq;
        int reply;
        byte[] data;
        int off;
        int len;

        int i32(int at) {
            int p = off + at;
            return (data[p] & 0xFF) | (data[p + 1] & 0xFF) << 8 | (data[p + 2] & 0xFF) << 16 | (data[p + 3] & 0xFF) << 24;
        }

        int i16(int at) {
            int p = off + at;
            return (short) ((data[p] & 0xFF) | (data[p + 1] & 0xFF) << 8);
        }

        int u8(int at) {
            return data[off + at] & 0xFF;
        }
    }

    /** Desembaralha e interpreta uma leitura. Devolve null se não for uma mensagem válida. */
    static Msg parse(byte[] b, int n) {
        if (n < 64) return null;
        byte[] key = {b[16], b[17], b[18], b[19]};
        for (int i = 4; i < 64; i++) b[i] ^= key[(i - 4) & 3];
        Msg m = new Msg();
        m.cmd = new String(new char[]{(char) b[7], (char) b[6], (char) b[5], (char) b[4]});
        m.data = b;
        m.off = 0;
        m.len = n;
        m.seq = m.i32(8);
        m.reply = m.i32(12);
        return m;
    }

    /** Monta uma mensagem; os campos do comando vêm em {@code fields}. */
    static final class Builder {
        private byte[] buf = new byte[256];
        private int pos = 20;

        Builder(String cmd, int replyTo) {
            buf[4] = (byte) cmd.charAt(3);
            buf[5] = (byte) cmd.charAt(2);
            buf[6] = (byte) cmd.charAt(1);
            buf[7] = (byte) cmd.charAt(0);
            put32(8, (BASE & 0xFFFF0000) | (RNG.nextInt() & 0xFFFF));
            put32(12, replyTo);
            put32(16, 0);
        }

        private void ensure(int extra) {
            if (pos + extra > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, pos + extra + 64)];
                System.arraycopy(buf, 0, nb, 0, pos);
                buf = nb;
            }
        }

        private void put32(int at, int v) {
            buf[at] = (byte) v;
            buf[at + 1] = (byte) (v >>> 8);
            buf[at + 2] = (byte) (v >>> 16);
            buf[at + 3] = (byte) (v >>> 24);
        }

        Builder i32(int v) {
            ensure(4);
            put32(pos, v);
            pos += 4;
            return this;
        }

        Builder i16(int v) {
            ensure(2);
            buf[pos] = (byte) v;
            buf[pos + 1] = (byte) (v >>> 8);
            pos += 2;
            return this;
        }

        Builder u8(int v) {
            ensure(1);
            buf[pos++] = (byte) v;
            return this;
        }

        Builder bytes(byte[] src, int off, int len) {
            ensure(len);
            System.arraycopy(src, off, buf, pos, len);
            pos += len;
            return this;
        }

        /** Finaliza: completa até 64 bytes, grava o tamanho e embaralha. */
        byte[] build() {
            int total = pos;
            byte[] out;
            if (total < 64) {
                out = new byte[64];
                RNG.nextBytes(out);
                System.arraycopy(buf, 0, out, 0, total);
                total = 64;
            } else {
                out = new byte[total];
                System.arraycopy(buf, 0, out, 0, total);
            }
            out[0] = (byte) total;
            out[1] = (byte) (total >>> 8);
            out[2] = (byte) (total >>> 16);
            out[3] = (byte) (total >>> 24);
            int k = RNG.nextInt();
            byte[] key = {(byte) (k >>> 24), (byte) (k >>> 16), (byte) (k >>> 8), (byte) k};
            for (int i = 4; i < 64; i++) out[i] ^= key[(i - 4) & 3];
            System.arraycopy(key, 0, out, 16, 4);
            return out;
        }
    }

    private Proto() {
    }
}
