package restservices;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


import com.mendix.core.Core;
import com.mendix.m2ee.log.ILogNode;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class RestServices {
	public static boolean isValidKey(String key) {
		//TODO:
		return true;
	}
	
	public static String identifierToRestURL(IMendixIdentifier id) {
		PublishedService service = RestServices.getServiceForEntity(id.getObject());
		
		// TODO Auto-generated method stub
		return null;
	}
	
	public static PublishedService getServiceForEntity(IMendixObject object) {
		// TODO Auto-generated method stub
		//TODO: look into super entitites as well!
		return null;
	}

	static final ILogNode LOG = Core.getLogger("RestPublisher");
	static Map<String, PublishedService> services = new HashMap<String, PublishedService>();
	static Map<String, PublishedService> servicesByEntity = new HashMap<String, PublishedService>();
}
