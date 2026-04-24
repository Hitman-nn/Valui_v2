package BotValui.Service.betboom;

import com.google.protobuf.ByteString;
import proto.betboom.Current;
import proto.betboom.Envelope;
import proto.betboom.ParentWrap;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class BetBoomSubscribeBuilder {
    private BetBoomSubscribeBuilder() {}

    public static final ByteString DEFAULT_SPORT_PARENT_BS =  ByteString.copyFromUtf8("c811dd");
    public static final ByteString DEFAULT_SPORT_CHILD_BS  =  ByteString.copyFromUtf8("e795d0");
    public static final ByteString DEFAULT_SPORT_ALL_BS =  ByteString.copyFromUtf8("b5b75c");

    /** OUTER=4 (SPORT_ALL): ParentWrap.inner = raw varints [type, id] (именно в таком порядке). */
    public static byte[] sportAllBytes(String parentHex, Current.TypeLine type, int id) {
        byte[] raw = twoVarints(type.getNumber(), id);

        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(parentHex)
                .setInner(ByteString.copyFrom(raw))
                .build();

        Envelope env = Envelope.newBuilder()
                .setRequestSportAll(wrap.toByteString())
                .build();

        return env.toByteArray();
    }

    public static byte[] sportAllBytes(Current.TypeLine type, int id) {
        return sportAllBytes(DEFAULT_SPORT_ALL_BS.toStringUtf8(), type, id);
    }

    public static String sportAllBase64(Current.TypeLine type, int sportId) {
        return Base64.getEncoder().encodeToString(sportAllBytes(type, sportId));
    }

    public static String sportAllBase64(String parentHex, Current.TypeLine type, int sportId) {
        return Base64.getEncoder().encodeToString(sportAllBytes(parentHex, type, sportId));
    }

    /** OUTER=6 (SPORT_TOURNAMENTS): ParentWrap.inner = Current(node_hex, type, sportId). */
    public static byte[] sportTournamentsBytes(String parentHex, String childHex, Current.TypeLine type, int sportId) {
        Current cur = Current.newBuilder()
                .setNodeHex(childHex)
                .setType(type)
                .setId(sportId)
                .build();

        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(parentHex)
                .setInner(cur.toByteString())
                .build();

        Envelope env = Envelope.newBuilder()
                .setRequestSportTournaments(wrap.toByteString())
                .build();

        return env.toByteArray();
    }

    public static byte[] sportTournamentsBytes(Current.TypeLine type, int sportId) {
        return sportTournamentsBytes(DEFAULT_SPORT_PARENT_BS.toStringUtf8(), DEFAULT_SPORT_CHILD_BS.toStringUtf8(), type, sportId);
    }

    public static String sportTournamentsBase64(Current.TypeLine type, int sportId) {
        return Base64.getEncoder().encodeToString(sportTournamentsBytes(type, sportId));
    }

    public static String sportTournamentsBase64(String parentHex, String childHex, Current.TypeLine type, int sportId) {
        return Base64.getEncoder().encodeToString(sportTournamentsBytes(parentHex, childHex, type, sportId));
    }

    /** OUTER=8 (TOURNAMENT_MATCHES). */
    public static byte[] tournamentMatchesBytes(String parentHex, String childHex, Current.TypeLine type, int tournamentId) {
        Current cur = Current.newBuilder()
                .setNodeHex(parentHex)
                .setType(type)
                .setId(tournamentId)
                .build();

        ParentWrap wrap = ParentWrap.newBuilder()
                .setParentHex(childHex)
                .setInner(cur.toByteString())
                .build();

        Envelope env = Envelope.newBuilder()
                .setRequestTournamentMatches(wrap.toByteString())
                .build();

        return env.toByteArray();
    }

    public static byte[] tournamentMatchesBytes(Current.TypeLine type, int tournamentId) {
        return tournamentMatchesBytes(DEFAULT_SPORT_PARENT_BS.toStringUtf8(), DEFAULT_SPORT_CHILD_BS.toStringUtf8(), type, tournamentId);
    }

    public static String tournamentMatchesBase64(Current.TypeLine type, int tournamentId) {
        return Base64.getEncoder().encodeToString(tournamentMatchesBytes(type, tournamentId));
    }

    public static String tournamentMatchesBase64(String parentHex, String childHex, Current.TypeLine type, int tournamentId) {
        return Base64.getEncoder().encodeToString(tournamentMatchesBytes(parentHex, childHex, type, tournamentId));
    }

    private static byte[] varintRaw(int v) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        long x = v & 0xFFFFFFFFL;
        while ((x & ~0x7FL) != 0) {
            os.write((int)((x & 0x7F) | 0x80));
            x >>>= 7;
        }
        os.write((int)x);
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
