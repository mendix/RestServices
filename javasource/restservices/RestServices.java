package restservices;

import java.io.File;
import java.security.AccessControlException;
import java.util.Collection;
import java.util.Date;
import java.util.regex.Pattern;

import org.codehaus.jackson.map.ObjectMapper;
import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class RestServices {
	public final static String HANDLERPATH = "rest/";

	public static final String UTF8 = "UTF-8";

	public static final String CONTENTTYPE_PARAM = "contenttype";
	
	public static final String ETAG_HEADER = "ETag";
	public static final String IFNONEMATCH_HEADER = "If-None-Match";
	public static final String ACCEPT_HEADER = "Accept";
	
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
	public static boolean isValidKey(String key) {
		//TODO:
		return true;
	}
	public static void serializeMember(IContext context, JSONObject target,
			IMendixObjectMember<?> member, IMetaObject viewType) throws Exception {
		if (context == null)
			throw new IllegalStateException("Context is null");

		Object value = member.getValue(context);
		String memberName = member.getName();

		//Primitive?
		if (!(member instanceof MendixObjectReference) && !(member instanceof MendixObjectReferenceSet)) {
			switch(viewType.getMetaPrimitive(member.getName()).getType()) {
			case AutoNumber:
			case Long:
			case Boolean:
			case Currency:
			case Float:
			case Integer:
				//Numbers or bools should never be null!
				if (value == null)
					throw new IllegalStateException("Primitive member " + member.getName() + " should not be null!");

				target.put(memberName, value);
				break;
			case Enum:
			case HashString:
			case String:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
				target.put(memberName, value);
				break;
			case DateTime:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
					
				target.put(memberName, (long)(Long)(((Date)value).getTime()));
				break;
			case Binary:
			default: 
				throw new IllegalStateException("Not supported Mendix Membertype for member " + memberName);
			}
		}
			
		/**
		 * Reference
		 */
		else if (member instanceof MendixObjectReference){
			if (value == null)
				target.put(memberName, JSONObject.NULL);
			
			IMendixIdentifier id = (IMendixIdentifier) value;
			
			throw new Exception("Not implemented yet"); //TODO:
		}
		
		/**
		 * Referenceset
		 */
		else if (member instanceof MendixObjectReferenceSet){
//			ArrayNode res = getMapper().createArrayNode();
			
	//		if (value == null)
		//		return res;
			
	/*		for(IMendixIdentifier id : (List<IMendixIdentifier>) value) {
				Object convertedValue = identifierConverter == null ? id.toLong() : identifierConverter.convertIdentifier(id);
				if (convertedValue != null)
					res.add(getMapper().valueToTree(convertedValue));
			}
	*/		throw new Exception("Not implemented yet"); //TODO:
			
		}
		
		else
			throw new IllegalStateException("Unimplemented membertype " + member.getClass().getSimpleName());
	}
}
