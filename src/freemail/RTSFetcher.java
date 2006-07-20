package freemail;

import freemail.fcp.FCPConnection;
import freemail.fcp.HighLevelFCPClient;
import freemail.utils.DateStringFactory;
import freemail.utils.PropsFile;
import freemail.utils.ChainedAsymmetricBlockCipher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;
import java.util.TimeZone;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigInteger;
import java.net.MalformedURLException;

import org.bouncycastle.util.encoders.Hex;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.engines.RSAEngine;
import org.bouncycastle.crypto.InvalidCipherTextException;

import freenet.support.io.LineReadingInputStream;

public class RTSFetcher {
	private String rtskey;
	private File contact_dir;
	private final SimpleDateFormat sdf;
	private static final int POLL_AHEAD = 3;
	private static final int PASSES_PER_DAY = 3;
	private static final int MAX_DAYS_BACK = 30;
	private static final String LOGFILE = "rtslog";
	private static final int RTS_MAX_SIZE = 2 * 1024 * 1024;
	private static final String RTS_UNPROC_PREFIX = "unprocessed_rts";
	private static final int RTS_MAX_ATTEMPTS = 15;
	private File accdir;
	private PropsFile accprops;

	RTSFetcher(String key, File ctdir, File ad) {
		this.rtskey = key;
		this.sdf = new SimpleDateFormat("dd MMM yyyy HH:mm:ss Z");
		this.contact_dir = ctdir;
		this.accdir = ad;
		this.accprops = AccountManager.getAccountFile(this.accdir);
	}
	
	public void poll() {
		this.fetch();
		this.handle_unprocessed();
	}
	
	private void handle_unprocessed() {
		File[] files = this.contact_dir.listFiles();
		
		int i;
		for (i = 0; i < files.length; i++) {
			if (!files[i].getName().startsWith(RTS_UNPROC_PREFIX))
				continue;
			if (this.handle_rts(files[i])) {
				files[i].delete();
			} else {
				String[] parts = files[i].getName().split(":", 2);
				
				int tries;
				if (parts.length < 2) {
					tries = 0;
				} else {
					tries = Integer.parseInt(parts[1]);
				}
				tries++;
				if (tries > RTS_MAX_ATTEMPTS) {
					System.out.println("Maximum attempts at handling RTS reached - deleting RTS");
					files[i].delete();
				} else {
					File newname = new File(this.contact_dir, RTS_UNPROC_PREFIX + ":" + tries);
					files[i].renameTo(newname);
				}
			}
		}
	}
	
	private void fetch() {
		int i;
		RTSLog log = new RTSLog(new File(this.contact_dir, LOGFILE));
		for (i = 1 - MAX_DAYS_BACK; i <= 0; i++) {
			String datestr = DateStringFactory.getOffsetKeyString(i);
			if (log.getPasses(datestr) < PASSES_PER_DAY) {
				this.fetch_day(log, datestr);
				// don't count passes for today since more
				// mail may arrive
				if (i < 0) {
					log.incPasses(datestr);
				}
			}
		}
		
		TimeZone gmt = TimeZone.getTimeZone("GMT");
		Calendar cal = Calendar.getInstance(gmt);
		cal.setTime(new Date());
		
		cal.add(Calendar.DAY_OF_MONTH, 0 - MAX_DAYS_BACK);
		log.pruneBefore(cal.getTime());
	}
	
	private void fetch_day(RTSLog log, String date) {
		HighLevelFCPClient fcpcli;
		fcpcli = new HighLevelFCPClient();
		
		String keybase;
		keybase = this.rtskey + date + "-";
		
		int startnum = log.getNextId(date);
		
		for (int i = startnum; i < startnum + POLL_AHEAD; i++) {
			System.out.println("trying to fetch "+keybase+i);
			
			File result = fcpcli.fetch(keybase+i);
			
			if (result != null) {
				System.out.println(keybase+i+": got RTS!");
				
				File rts_dest = new File(this.contact_dir, RTS_UNPROC_PREFIX + "-" + log.getAndIncUnprocNextId()+":0");
				
				// stick this message in the RTS 'inbox'
				if (result.renameTo(rts_dest)) {
					// provided that worked, we can move on to the next RTS message
					log.incNextId(date);
				}
			} else {
				System.out.println(keybase+i+": no RTS.");
			}
		}
	}
	
	
	
	private boolean handle_rts(File rtsmessage) {
		// sanity check!
		if (!rtsmessage.exists()) return false;
		
		if (rtsmessage.length() > RTS_MAX_SIZE) {
			System.out.println("RTS Message is too large - discarding!");
			return true;
		}
		
		// decrypt
		byte[] plaintext;
		try {
			plaintext = decrypt_rts(rtsmessage);
		} catch (IOException ioe) {
			System.out.println("Error reading RTS message!");
			return false;
		} catch (InvalidCipherTextException icte) {
			System.out.println("Could not decrypt RTS message - discarding.");
			return true;
		}
		
		File rtsfile = null;
		byte[] their_encrypted_sig;
		int messagebytes = 0;
		try {
			rtsfile = File.createTempFile("rtstmp", "tmp", Freemail.getTempDir());
			
			ByteArrayInputStream bis = new ByteArrayInputStream(plaintext);
			LineReadingInputStream lis = new LineReadingInputStream(bis);
			PrintStream ps = new PrintStream(new FileOutputStream(rtsfile));
			
			String line;
			while (true) {
				line = lis.readLine(200, 200);
				messagebytes += lis.getLastBytesRead();
				
				if (line == null || line.equals("")) break;
				System.out.println(line);
				
				ps.println(line);
			}
			
			ps.close();
			
			if (line == null) {
				// that's not right, we shouldn't have reached the end of the file, just the blank line before the signature
				
				System.out.println("Couldn't find signature on RTS message - ignoring!");
				rtsfile.delete();
				return true;
			}
			
			their_encrypted_sig = new byte[bis.available()];
			
			int totalread = 0;
			while (true) {
				int read = bis.read(their_encrypted_sig, totalread, bis.available());
				if (read <= 0) break;
				totalread += read;
			}
			
			System.out.println("read "+totalread+" bytes of signature");
			
			bis.close();
		} catch (IOException ioe) {
			System.out.println("IO error whilst handling RTS message. "+ioe.getMessage());
			ioe.printStackTrace();
			if (rtsfile != null) rtsfile.delete();
			return false;
		}
		
		
		
		PropsFile rtsprops = new PropsFile(rtsfile);
		
		try {
			validate_rts(rtsprops);
		} catch (Exception e) {
			System.out.println("RTS message does not contain vital information: "+e.getMessage()+" - discarding");
			rtsfile.delete();
			return true;
		}
		
		// verify the signature
		String their_mailsite = rtsprops.get("mailsite");
		
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException alge) {
			System.out.println("No MD5 implementation available - sorry, Freemail cannot work!");
			rtsfile.delete();
			return false;
		}
		md.update(plaintext, 0, messagebytes);
		byte[] our_hash = md.digest();
		
		HighLevelFCPClient fcpcli = new HighLevelFCPClient();
		
		System.out.println("Trying to fetch sender's mailsite: "+their_mailsite);
		
		File msfile = fcpcli.fetch(their_mailsite);
		if (msfile == null) {
			// oh well, try again in a bit
			return false;
		}
		
		PropsFile mailsite = new PropsFile(msfile);
		String their_exponent = mailsite.get("asymkey.pubexponent");
		String their_modulus = mailsite.get("asymkey.modulus");
		
		if (their_exponent == null || their_modulus == null) {
			System.out.println("Mailsite fetched successfully but missing vital information! Discarding this RTS.");
			msfile.delete();
			rtsfile.delete();
			return true;
		}
		
		RSAKeyParameters their_pubkey = new RSAKeyParameters(false, new BigInteger(their_modulus, 10), new BigInteger(their_exponent, 10));
		AsymmetricBlockCipher deccipher = new RSAEngine();
		deccipher.init(false, their_pubkey);
		
		byte[] their_hash;
		try {
			their_hash = deccipher.processBlock(their_encrypted_sig, 0, their_encrypted_sig.length);
		} catch (InvalidCipherTextException icte) {
			System.out.println("It was not possible to decrypt the signature of this RTS message. Discarding the RTS message.");
			msfile.delete();
			rtsfile.delete();
			return true;
		}
		
		// finally we can now check that our hash and their hash
		// match!
		if (their_hash.length != our_hash.length) {
			System.out.println("The signature of the RTS message is not valid. Discarding the RTS message.");
			msfile.delete();
			rtsfile.delete();
			return true;
		}
		int i;
		for (i = 0; i < their_hash.length; i++) {
			if (their_hash[i] != our_hash[i]) {
				System.out.println("The signature of the RTS message is not valid. Discarding the RTS message.");
				msfile.delete();
				rtsfile.delete();
				return true;
			}
		}
		System.out.println("Signature valid :)");
		// the signature is valid! Hooray!
		// Now verify the message is for us
		String our_mailsite_keybody;
		try {
			our_mailsite_keybody = new FreenetURI(this.accprops.get("mailsite.pubkey")).getKeyBody();
		} catch (MalformedURLException mfue) {
			System.out.println("Local mailsite URI is invalid! Corrupt account file?");
			msfile.delete();
			rtsfile.delete();
			return false;
		}
		if (!rtsprops.get("to").equals(our_mailsite_keybody)) {
			System.out.println("Recieved an RTS message that was not intended for the recipient. Discarding.");
			msfile.delete();
			rtsfile.delete();
			return true;
		}
		
		System.out.println("Original message intended for us :)");
		
		// create the inbound contact
		FreenetURI their_mailsite_furi;
		try {
			their_mailsite_furi = new FreenetURI(their_mailsite);
		} catch (MalformedURLException mfue) {
			System.out.println("Mailsite in the RTS message is not a valid Freenet URI. Discarding RTS message.");
			msfile.delete();
			rtsfile.delete();
			return true;
		}
		InboundContact ibct = new InboundContact(this.contact_dir, their_mailsite_furi);
		
		ibct.setProp("commssk", rtsprops.get("commssk"));
		ibct.setProp("ackssk", rtsprops.get("ackssk"));
		ibct.setProp("ctsksk", rtsprops.get("ctsksk"));
		
		msfile.delete();
		rtsfile.delete();
		
		System.out.println("Inbound contact created!");
		
		return true;
	}
	
	private byte[] decrypt_rts(File rtsmessage) throws IOException, InvalidCipherTextException {
		byte[] ciphertext = new byte[(int)rtsmessage.length()];
		FileInputStream fis = new FileInputStream(rtsmessage);
		int read = 0;
		while (read < rtsmessage.length()) {
			read += fis.read(ciphertext, read, (int)rtsmessage.length() - read);
		}
		
		RSAKeyParameters ourprivkey = AccountManager.getPrivateKey(this.accdir);
		
		// decrypt it
		AsymmetricBlockCipher deccipher = new RSAEngine();
		deccipher.init(false, ourprivkey);
		byte[] plaintext = ChainedAsymmetricBlockCipher.decrypt(deccipher, ciphertext);
		
		return plaintext;
	}
	
	/*
	 * Make sure an RTS file has all the right properties in it
	 * If any are missing, throw an exception which says which are missing
	 */
	private void validate_rts(PropsFile rts) throws Exception {
		StringBuffer missing = new StringBuffer();
		
		if (rts.get("commssk") == null) {
			missing.append("commssk, ");
		}
		if (rts.get("ackssk") == null) {
			missing.append("ackssk, ");
		}
		if (rts.get("messagetype") == null) {
			missing.append("messagetype, ");
		}
		if (rts.get("to") == null) {
			missing.append("to, ");
		}
		if (rts.get("mailsite") == null) {
			missing.append("mailsite, ");
		}
		if (rts.get("ctsksk") == null) {
			missing.append("ctsksk, ");
		}
		
		if (missing.length() == 0) return;
		throw new Exception(missing.toString().substring(0, missing.length() - 2));
	}
}
