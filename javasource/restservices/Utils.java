package restservices;

import java.io.File;
import java.security.AccessControlException;
import java.util.regex.Pattern;

import org.codehaus.jackson.map.ObjectMapper;

import com.mendix.core.Core;

public class Utils {

	public static String getResourceFilePath() {
		if (Utils.basepath == null) {
			Utils.basepath = Core.getConfiguration().getResourcesPath().getPath();
			String alt = Utils.basepath.replaceFirst(Pattern.quote("deployment" + File.separator + "model"), "");
			try {
				if (new File(alt).exists()) //prefer not to use the deployment dir for local deployments
					Utils.basepath = alt;
			}
			catch(AccessControlException e) {
				//nothing. Exception will be thrown if cloud security is enabled
			}
			Utils.basepath += File.separator + "RestServices" + File.separator;
		}
		
		return Utils.basepath;
	}

	private static String basepath = null;
	private final static ObjectMapper mapper = new ObjectMapper();
	
	public static ObjectMapper getJsonMapper() {
		return mapper;
	}

	static String getShortMemberName(String memberName) {
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
		return url.substring(i + 1);
	}

}
