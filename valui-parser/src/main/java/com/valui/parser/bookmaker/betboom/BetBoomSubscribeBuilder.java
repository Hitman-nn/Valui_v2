package com.valui.parser.bookmaker.betboom;

import com.google.protobuf.ByteString;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.ParentWrap;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class BetBoomSubscribeBuilder {

    public static final String DEFAULT_SPORT_PARENT_HEX = "c811dd";
    public static final String DEFAULT_SPORT_CHILD_HEX  = "e795d0";
    public static final String DEFAULT_SPORT_ALL_HEX    = "b5b75c";

    private BetBoomSubscribeBuilder() {}

    /** OUTER=4 (SPORT_ALL): ParentWrap.inner = raw varints [type, id]. */
    public static byte[] sportAllBytes(Current.TypeLine type, int id) {
        byte[] raw = twoVarints(type.getNumber(), id);
        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(DEFAULT_SPORT_ALL_HEX)
                .setInner(ByteString.copyFrom(raw))
                .build();
        return Envelope.newBuilder().setRequestSportAll(wrap.toByteString()).build().toByteArray();
    }

    /** OUTER=6 (SPORT_TOURNAMENTS): ParentWrap.inner = Current(node_hex, type, sportId). */
    public static byte[] sportTournamentsBytes(Current.TypeLine type, int sportId) {
        Current cur = Current.newBuilder()
                .setNodeHex(DEFAULT_SPORT_CHILD_HEX)
                .setType(type)
                .setId(sportId)
                .build();
        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(DEFAULT_SPORT_PARENT_HEX)
                .setInner(cur.toByteString())
                .build();
        return Envelope.newBuilder().setRequestSportTournaments(wrap.toByteString()).build().toByteArray();
    }

    /** OUTER=8 (TOURNAMENT_MATCHES). */
    public static byte[] tournamentMatchesBytes(Current.TypeLine type, int tournamentId) {
        Current cur = Current.newBuilder()
                .setNodeHex(DEFAULT_SPORT_PARENT_HEX)
                .setType(type)
                .setId(tournamentId)
                .build();
        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(DEFAULT_SPORT_CHILD_HEX)
                .setInner(cur.toByteString())
                .build();
        return Envelope.newBuilder().setRequestTournamentMatches(wrap.toByteString()).build().toByteArray();
    }

    public static String sportAllBase64(Current.TypeLine type, int id) {
        return Base64.getEncoder().encodeToString(sportAllBytes(type, id));
    }

    public static String sportTournamentsBase64(Current.TypeLine type, int sportId) {
        return Base64.getEncoder().encodeToString(sportTournamentsBytes(type, sportId));
    }

    public static String tournamentMatchesBase64(Current.TypeLine type, int tournamentId) {
        return Base64.getEncoder().encodeToString(tournamentMatchesBytes(type, tournamentId));
    }

    private static byte[] varintRaw(int v) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        long x = v & 0xFFFFFFFFL;
        while ((x & ~0x7FL) != 0) {
            os.write((int) ((x & 0x7F) | 0x80));
            x >>>= 7;
        }
        os.write((int) x);
        return os.toByteArray();
    }

    private static byte[] twoVarints(int a, int b) {
        byte[] va = varintRaw(a);
        byte[] vb = varintRaw(b);
        byte[] out = new byte[va.length + vb.length];
        System.arraycopy(va, 0, out, 0, va.length);
        System.arraycopy(vb, 0, out, va.length, vb.length);
        return out;
    }
}
