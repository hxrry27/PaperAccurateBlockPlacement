package net.dungeondev.accurateblockplacement;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class Payloads {

	private Payloads() {
	}

	static byte[] carpetHello(int version, String brand) {
		return build(dos -> {
			writeVarInt(dos, version);
			writeMcString(dos, brand);
		});
	}

	static byte[] carpetRule(String compoundName, String value, String manager, String rule) {
		return build(dos -> {
			writeVarInt(dos, 1);
			dos.writeByte(10); 
			dos.writeUTF(compoundName); // this root compound IS named, unlike the servux one below
			writeStringTag(dos, "Value", value);
			writeStringTag(dos, "Manager", manager);
			writeStringTag(dos, "Rule", rule);
			dos.writeByte(0); 
		});
	}

	static byte[] servuxMetadata(int packetType, int protocolVersion, String brand, String channel) {
		return build(dos -> {
			writeVarInt(dos, packetType);
			dos.writeByte(10); 
			writeIntTag(dos, "version", protocolVersion);
			writeStringTag(dos, "servux", brand);
			writeStringTag(dos, "name", brand);
			writeStringTag(dos, "id", channel);
			dos.writeByte(0); 
		});
	}

	@FunctionalInterface
	private interface Body {
		void write(DataOutputStream dos) throws IOException;
	}

	private static byte[] build(Body body) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			DataOutputStream dos = new DataOutputStream(out);
			body.write(dos);
			dos.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e); 
		}
		return out.toByteArray();
	}

	static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	static void writeMcString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	private static void writeStringTag(DataOutputStream dos, String name, String value) throws IOException {
		dos.writeByte(8); 
		dos.writeUTF(name);
		dos.writeUTF(value);
	}

	private static void writeIntTag(DataOutputStream dos, String name, int value) throws IOException {
		dos.writeByte(3); 
		dos.writeUTF(name);
		dos.writeInt(value);
	}
}
