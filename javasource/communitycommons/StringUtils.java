package communitycommons;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.owasp.validator.html.AntiSamy;
import org.owasp.validator.html.CleanResults;
import org.owasp.validator.html.Policy;

import system.proxies.FileDocument;

import com.google.common.base.Function;
import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.MendixRuntimeException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.proxies.XSSPolicy;

public class StringUtils
{

	public static final String	HASH_ALGORITHM	= "SHA-256";
	
	public static String hash(String value, int length) throws NoSuchAlgorithmException, DigestException
	{
		byte[] inBytes = value.getBytes();
	    byte[] outBytes = new byte[length];

	    MessageDigest alg=MessageDigest.getInstance(HASH_ALGORITHM);
	    alg.update(inBytes);

	    alg.digest(outBytes, 0, length);
	    return String.valueOf(outBytes);
	}

	public static String regexReplaceAll(String haystack, String needleRegex,
			String replacement)
	{
		Pattern pattern = Pattern.compile(needleRegex);
		Matcher matcher = pattern.matcher(haystack);
		return matcher.replaceAll(replacement);
	}

	public static boolean regexTest(String value, String regex)
	{
		return Pattern.matches(regex, value);
	}

	public static String leftPad(String value, Long amount, String fillCharacter)
	{
		if (fillCharacter == null || fillCharacter.length() == 0) {
			return org.apache.commons.lang3.StringUtils.leftPad(value, amount.intValue(), " ");
		}
		return org.apache.commons.lang3.StringUtils.leftPad(value, amount.intValue(), fillCharacter);
	}
	
	public static String rightPad(String value, Long amount, String fillCharacter)
	{
		if (fillCharacter == null || fillCharacter.length() == 0) {
			return org.apache.commons.lang3.StringUtils.rightPad(value, amount.intValue(), " ");
		}
		return org.apache.commons.lang3.StringUtils.rightPad(value, amount.intValue(), fillCharacter);
	}

	public static String randomString(int length)
	{
		return org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric(length);
	}

	public static String substituteTemplate(final IContext context, String template,
			final IMendixObject substitute, final boolean HTMLEncode, final String datetimeformat) {
		return regexReplaceAll(template, "\\{(@)?([\\w./]+)\\}", new Function<MatchResult, String>() {

			@Override
			public String apply(MatchResult match)
			{
				String value;
				String path = match.group(2);
				if (match.group(1) != null)
					value = String.valueOf(Core.getConfiguration().getConstantValue(path));
				else {
					try
					{
						value = ORM.getValueOfPath(context, substitute, path,	datetimeformat);
					}
					catch (Exception e)
					{
						throw new RuntimeException(e);
					}
				}
				return HTMLEncode ? HTMLEncode(value) : value;
			}
			
		});
	}
	
	public static String regexReplaceAll(String source, String regexString, Function<MatchResult, String> replaceFunction)  {
		if (source == null || source.trim().isEmpty()) // avoid NPE's, save CPU
			return "";
	
		StringBuffer resultString = new StringBuffer();
		Pattern regex = Pattern.compile(regexString);
		Matcher regexMatcher = regex.matcher(source);
		
		while (regexMatcher.find()) {
			MatchResult match = regexMatcher.toMatchResult();
			String value = replaceFunction.apply(match); 
			regexMatcher.appendReplacement(resultString, Matcher.quoteReplacement(value));
		}
		regexMatcher.appendTail(resultString);
	
		return resultString.toString();
	}

	public static String HTMLEncode(String value)
	{
		return StringEscapeUtils.escapeHtml4(value);
	}

	public static String randomHash()
	{
		return UUID.randomUUID().toString();
	}

	public static String base64Decode(String encoded)
	{
		if (encoded == null)
			return null;
		return new String(Base64.decodeBase64(encoded.getBytes()));
	}

	public static void base64DecodeToFile(IContext context, String encoded, FileDocument targetFile) throws Exception
	{
		if (targetFile == null)
			throw new IllegalArgumentException("Source file is null");
		if (encoded == null)
			throw new IllegalArgumentException("Source data is null");
		
		byte [] decoded = Base64.decodeBase64(encoded.getBytes());
		Core.storeFileDocumentContent(context, targetFile.getMendixObject(), new ByteArrayInputStream(decoded));
	}

	public static String base64Encode(String value)
	{
		if (value == null)
			return null;
		return new String(Base64.encodeBase64(value.getBytes()));
	}

	public static String base64EncodeFile(IContext context, FileDocument file) throws IOException
	{
		if (file == null)
			throw new IllegalArgumentException("Source file is null");
		if (!file.getHasContents())
			throw new IllegalArgumentException("Source file has no contents!");
		InputStream f = Core.getFileDocumentContent(context, file.getMendixObject());
		return new String(Base64.encodeBase64(IOUtils.toByteArray(f)));		
	}

	public static String stringFromFile(IContext context, FileDocument source) throws IOException
	{
		if (source == null)
			return null;
		InputStream f = Core.getFileDocumentContent(context, source.getMendixObject());
		return org.apache.commons.io.IOUtils.toString(f);
	}

	public static void stringToFile(IContext context, String value, FileDocument destination) 
	{
		if (destination == null)
			throw new IllegalArgumentException("Destination file is null");
		if (value == null)
			throw new IllegalArgumentException("Value to write is null");
		Core.storeFileDocumentContent(context, destination.getMendixObject(), IOUtils.toInputStream(value));
	}

	public static String HTMLToPlainText(String html) throws IOException
	{
		if (html == null)
			return "";
		final StringBuffer result = new StringBuffer();
		
    HTMLEditorKit.ParserCallback callback = 
      new HTMLEditorKit.ParserCallback () {
        @Override
				public void handleText(char[] data, int pos) {
            result.append(data); //TODO: needds to be html entity decode?
        }
        
        @Override
        public void handleComment(char[] data, int pos) {
        	//Do nothing
        }
        
        @Override
				public void handleError(String errorMsg, int pos) {
        	//Do nothing
        }
        
        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet a, int pos) {
        		 if (tag == HTML.Tag.BR)
        			 result.append("\r\n");
        }
        
        @Override
        public void handleEndTag(HTML.Tag tag, int pos){
     		 if (tag == HTML.Tag.P)
    			 result.append("\r\n");
        }
    };
    
    new ParserDelegator().parse(new StringReader(html), callback, true);
		
    return result.toString();
	}

	public static String XSSSanitize(String html, XSSPolicy policy)
			throws Exception {
		if (html == null)
			return "";
		// return HtmlSanitizer.sanitize(html);
		String policyString = policy == null ? "tinymce" : policy.toString()
				.toLowerCase();
		return XSSSanitize(html, policyString);
	}
	
	public static String XSSSanitize(String html, String policyString)
			throws Exception {
		if (html == null)
			return "";
		if (policyString == null)
			throw new Exception("Unable to perform XSS sanitization: policyString is null");
		
		String filename = Core.getConfiguration().getResourcesPath() + File.separator
				+ "communitycommons" + File.separator + "antisamy"
				+ File.separator + "antisamy-" + policyString + "-1.4.4.xml";
	
		AntiSamy as = new AntiSamy(); // Create AntiSamy object
		Policy p = Policy.getInstance(filename);
		try {
			CleanResults cr = as.scan(html, p, AntiSamy.SAX);
			return cr.getCleanHTML();
		} catch (Exception e) {
			throw new Exception("Unable to perform XSS sanitization: "
					+ e.getMessage(), e);
		}
	}

	private static final String ALPHA_CAPS  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALPHA   = "abcdefghijklmnopqrstuvwxyz";
    private static final String NUM     = "0123456789";
    private static final String SPL_CHARS   = "!@#$%^&*_=+-/";
	/**
	 * Returns a random strong password containing at least one number, lowercase character, uppercase character and strange character
	 * @param length
	 * @return
	 */
	public static String randomStrongPassword(int minLen, int maxLen, int noOfCAPSAlpha, 
            int noOfDigits, int noOfSplChars) {
        if(minLen > maxLen)
            throw new IllegalArgumentException("Min. Length > Max. Length!");
        if( (noOfCAPSAlpha + noOfDigits + noOfSplChars) > minLen )
            throw new IllegalArgumentException
            ("Min. Length should be atleast sum of (CAPS, DIGITS, SPL CHARS) Length!");
        Random rnd = new Random();
        int len = rnd.nextInt(maxLen - minLen + 1) + minLen;
        char[] pswd = new char[len];
        int index = 0;
        for (int i = 0; i < noOfCAPSAlpha; i++) {
            index = getNextIndex(rnd, len, pswd);
            pswd[index] = ALPHA_CAPS.charAt(rnd.nextInt(ALPHA_CAPS.length()));
        }
        for (int i = 0; i < noOfDigits; i++) {
            index = getNextIndex(rnd, len, pswd);
            pswd[index] = NUM.charAt(rnd.nextInt(NUM.length()));
        }
        for (int i = 0; i < noOfSplChars; i++) {
            index = getNextIndex(rnd, len, pswd);
            pswd[index] = SPL_CHARS.charAt(rnd.nextInt(SPL_CHARS.length()));
        }
        for(int i = 0; i < len; i++) {
            if(pswd[i] == 0) {
                pswd[i] = ALPHA.charAt(rnd.nextInt(ALPHA.length()));
            }
        }
        return String.valueOf(pswd);
    }
	private static int getNextIndex(Random rnd, int len, char[] pswd) {
        int index = rnd.nextInt(len);
        while(pswd[index = rnd.nextInt(len)] != 0);
        return index;
    }

	public static String encryptString(String key, String valueToEncrypt) throws Exception
	{
		if (valueToEncrypt == null) 
			return null;
		if (key == null)
			throw new MendixRuntimeException("Key should not be empty");
		if (key.length() != 16)
			throw new MendixRuntimeException("Key length should be 16");
		Cipher c = Cipher.getInstance("AES/CBC/PKCS5PADDING");
		SecretKeySpec k = new SecretKeySpec(key.getBytes(), "AES");
		c.init(Cipher.ENCRYPT_MODE, k);
		byte[] encryptedData = c.doFinal(valueToEncrypt.getBytes());
		byte[] iv = c.getIV();
		
		return new String(Base64.encodeBase64(iv)) + ";" + new String(Base64.encodeBase64(encryptedData));
	}

	public static String decryptString(String key, String valueToDecrypt) throws Exception
	{
		if (valueToDecrypt == null)
			return null;
		if (key == null)
			throw new MendixRuntimeException("Key should not be empty");
		if (key.length() != 16)
			throw new MendixRuntimeException("Key length should be 16");
		Cipher c = Cipher.getInstance("AES/CBC/PKCS5PADDING");
		SecretKeySpec k = new SecretKeySpec(key.getBytes(), "AES");
		String[] s = valueToDecrypt.split(";");
		if (s.length < 2) //Not an encrypted string, just return the original value.
			return valueToDecrypt;
		byte[] iv = Base64.decodeBase64(s[0].getBytes());
		byte[] encryptedData = Base64.decodeBase64(s[1].getBytes());
		c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(iv));
		return new String(c.doFinal(encryptedData));
	}

	public static String generateHmacSha256Hash(String key, String valueToEncrypt)
	{
		try {
			SecretKeySpec secretKey = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(secretKey);
			mac.update(valueToEncrypt.getBytes("UTF-8"));
			byte[] hmacData = mac.doFinal();

            return new String(Base64.encodeBase64(hmacData));
		}
		catch (Exception e) {
			throw new RuntimeException("CommunityCommons::EncodeHmacSha256::Unable to encode: " + e.getMessage(), e);
		}
	}
	
	public static String escapeHTML(String input) {
		return input.replace("\"", "&quot;")
					.replace("&", "&amp;")
					.replace("<", "&lt;")
					.replace(">", "&gt;")
					.replace("'", "&#39;");// notice this one: for xml "&#39;" would be "&apos;" (http://blogs.msdn.com/b/kirillosenkov/archive/2010/03/19/apos-is-in-xml-in-html-use-39.aspx)
		// OWASP also advises to escape "/" but give no convincing reason why: https://www.owasp.org/index.php/XSS_%28Cross_Site_Scripting%29_Prevention_Cheat_Sheet
	}

	public static String regexQuote(String unquotedLiteral) {
		return Pattern.quote(unquotedLiteral);
	}
}
