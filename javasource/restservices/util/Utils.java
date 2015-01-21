package restservices.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.digest.DigestUtils;

import restservices.RestServices;

import com.google.common.base.Preconditions;
import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.UserAction;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
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
		//a bit naive maybe.... 
		return url != null && (url.startsWith("http://") || url.startsWith("https://"));
	}

	public static String getKeyFromUrl(String url) {
		int i = url.lastIndexOf('/'); 
		if (i == -1 || i == url.length() -1)
			throw new RuntimeException("Not a key containing url: " + url);
		String key = url.substring(i + 1);
		i = key.indexOf('?');
		if (i > -1)
			key = key.substring(0, i);
		i = key.indexOf('#');
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
		return key != null && !key.trim().isEmpty() && key.length() > 0 && key.length() < 400; //MWE: warn: magic number!
	}

	public static boolean isEmpty(String value) {
		return value == null || value.trim().isEmpty();
	}

	public static boolean isNotEmpty(String value) {
		return !isEmpty(value);
	}

	public static Map<String, String> getArgumentTypes(String mf) {
		if (!microflowExists(mf)) //known args is usually empty map for non existing microflow :S.
			throw new IllegalArgumentException("Unknown microflow: " + mf);

		Map<String, IDataType> knownArgs = Core.getInputParameters(mf);;
		
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
		if (value == null) {
			return "";
		}
		try {
			//See: http://www.w3.org/TR/html4/interact/forms.html#h-17.13.4.1
			//See: http://docs.oracle.com/javase/7/docs/api/java/net/URLEncoder.html
			//See: http://tools.ietf.org/html/rfc3986#section-2.3
			//URLEncoder.encode differts on 2 things, spaces are encoded with +, as described in form-encoding.
			//This is replaces by %20, since that is always save.
			//Besides that, URLEncoder 'forgets' to encode '*', which is a reserved character..... So also replace it.
			return URLEncoder.encode(value, RestServices.UTF8).replace("+", "%20").replace("*", "%2A");
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
		Preconditions.checkNotNull(url, "URL should not be null");
		return url.endsWith("/") ? url : url + "/";
	}
	
	public static String removeTrailingSlash(String relativeUrl) {
		return relativeUrl.replaceAll("\\/$", "");
	}

	public static String removeLeadingSlash(String relativeUrl) {
		return relativeUrl.replaceAll("^\\/", "");
	}
	
	public static String removeLeadingAndTrailingSlash(String relativeUrl) {
		return removeLeadingSlash(removeTrailingSlash(relativeUrl));
	}

	public static String nullToEmpty(String statusText) {
		return statusText == null ? "" : statusText;
	}

	public static String getRequestUrl(HttpServletRequest request) {
		return request.getRequestURL().toString() + (Utils.isEmpty(request.getQueryString()) ? "" : "?" + request.getQueryString());
	}

	public static boolean isSystemAttribute(String key) {
		if ("createdDate".equals(key)
			|| "changedDate".equals(key)
			|| "System.owner".equals(key)
			|| "System.changedBy".equals(key)) {
			return true;
		}
		return false;
	}

	public static boolean microflowExists(String mf) {
		return Core.getMicroflowNames().contains(mf);
	}
	
	public static boolean hasDataAccess(IMetaObject meta, IContext context) {
		return meta.hasMetaDataAccess(context);
	}
	
	public static <T> T withSessionCache(IContext c, final Function<T> worker) throws Exception {
		try {
			return Core.executeSync(new UserAction<T>(c) {

				@Override
				public T executeAction() throws Exception {
					return worker.apply();
				}
			});
		}
		catch (Exception e) {
			//executeSync wraps all thrown exeption 2 times in a CoreRuntimeException, unwrap it...
			Throwable cause1 = e.getCause();
			if (cause1 == null || cause1.getCause() == null)
				throw e;
			else if (cause1.getCause() instanceof Exception)
				throw (Exception) cause1.getCause();
			else
				throw new RuntimeException(e);
		}
	}
	
	public static void sortIdList(List<IMendixIdentifier> ids) {
		Collections.sort(ids, new Comparator<IMendixIdentifier>() {

			@Override
			public int compare(IMendixIdentifier o1,
					IMendixIdentifier o2) {
				if (o2 == null && o1 == null)
					return 0;
				if (o2 == null)
					return -1;
				if (o1 == null)
					return 1;
				return Long.compare(o1.toLong(), o2.toLong());
			}
			
		});
	}
}
