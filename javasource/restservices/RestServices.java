package restservices;

import java.io.File;
import java.security.AccessControlException;
import java.util.regex.Pattern;

import org.codehaus.jackson.map.ObjectMapper;

import com.mendix.core.Core;

public class RestServices {
	final static String HANDLERPATH = "rest";
	
	private static String basepath = null;
	
	public static String getResourceFilePath() {
		if (basepath == null) {
			basepath = Core.getConfiguration().getResourcesPath().getPath();
			String alt = basepath.replaceFirst(Pattern.quote("deployment" + File.separator + "model"), "");
			try {
				if (new File(alt).exists()) //prefer not to use the deployment dir for local deployments
					basepath = alt;
			}
			catch(AccessControlException e) {
				//nothing. Exception will be thrown if cloud security is enabled
			}
			basepath += File.separator + "RestServices" + File.separator;
		}
		
		return basepath;
	}
	
	private final static ObjectMapper mapper = new ObjectMapper();
	public static ObjectMapper getJsonMapper() {
		return mapper;
	}
}
