package restservices.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.codec.digest.DigestUtils;

import restservices.RestServices;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class Utils {

	public static String getShortMemberName(String memberName) {
		return memberName.replaceFirst("^.+\\.", "");
	}

	public static String autoGenerateLink(String value) {
		if (isUrl(value))
			return "<a href='"+ value+ "'>" + value+ "</a>";
		return value;
	}

	public static boolean isUrl(String url) {
		//a bit naive maybe.... //TODO:
		return url != null && (url.startsWith("http://") || url.startsWith("https://"));
	}

	public static String getKeyFromUrl(String url) {
		int i = url.lastIndexOf('/'); //TODO: naive as well...
		if (i == -1 || i == url.length() -1)
			throw new RuntimeException("Not a key containing url: " + url);
		String key = url.substring(i + 1);
		i = key.indexOf('?');
		if (i > -1)
			key = key.substring(0, i);
		return key;
	}

	public static void copyAttributes(IContext context, IMendixObject source, IMendixObject target)
	{
		if (source == null)
			throw new IllegalStateException("source is null");
		if (target == null)
			throw new IllegalStateException("target is null");
		
		for(IMetaPrimitive e : target.getMetaObject().getMetaPrimitives()) {
			if (!source.hasMember(e.getName()))
				continue;
			if (e.isVirtual() || e.getType() == PrimitiveType.AutoNumber)
				continue;
			
			target.setValue(context, e.getName(), source.getValue(context, e.getName()));
		}
	}

	public static IDataType getFirstArgumentType(String microflow) {
		Map<String, IDataType> args = Core.getInputParameters(microflow); //if microflow does not exist, this returns empty map instead of null

		if (null == Core.getReturnType(microflow) || args == null)
			throw new RuntimeException("Unknown microflow: " + microflow);

		if (args.size() == 0)
			throw new RuntimeException("Microflow " + microflow + " should have at least one argument!");
		
		return args.entrySet().iterator().next().getValue();
	}

	public static String getMD5Hash(String jsonString)
			throws UnsupportedEncodingException {
		return DigestUtils.md5Hex(jsonString.getBytes(RestServices.UTF8));
	}

	public static boolean isValidKey(String key) {
		return key != null && !key.trim().isEmpty() && key.length() > 0 && key.length() < 400; //TODO: bit arbitrary
	}

	public static boolean isEmpty(String value) {
		return value == null || value.trim().isEmpty();
	}

	public static boolean isNotEmpty(String value) {
		return !isEmpty(value);
	}

	public static Map<String, String> getArgumentTypes(String mf) {
		Map<String, IDataType> knownArgs = null;
		try {
			knownArgs = Core.getInputParameters(mf);
		}
		catch(IllegalArgumentException e) {
			//ignore, mf does not exist.
		}
		if (knownArgs == null || null == Core.getReturnType(mf)) //known args is usually empty map for non existing microflow :S.
			throw new IllegalArgumentException("Unknown microflow");
		
		Map<String, String> args = new HashMap<String, String>();
		for(Entry<String, IDataType> e : knownArgs.entrySet()) {
			args.put(e.getKey(), 
					e.getValue().isMendixObject() 
					? e.getValue().getObjectType() 
					: e.getValue().isList() 
						? e.getValue().getObjectType() + "*"
						: e.getValue().getType().toString()
			);
		}
		return args;
	}

	public static String urlEncode(String value) {
		try {
			return URLEncoder.encode(value, RestServices.UTF8);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static String urlDecode(String value)
	{
		try
		{
			return URLDecoder.decode(value, RestServices.UTF8);
		}
		catch (UnsupportedEncodingException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static String appendParamToUrl(String url, String paramName,
			String paramValue) {
		return url + (url.contains("?") ? "&" : "?") + urlEncode(paramName) + "=" + urlEncode(paramValue);
	}

	public static String appendSlashToUrl(String url) {
		return url.endsWith("/") ? url : url + "/";
	}

	public static String nullToEmpty(String statusText) {
		return statusText == null ? "" : statusText;
	}

	
}
