package restservices;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;


import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.log.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class RestServices {
	public static boolean isValidKey(String key) {
		//TODO:
		return true;
	}
	
	public static String identifierToRestURL(IMendixIdentifier id) throws CoreException {
		if (id == null)
			return null;
		
		PublishedService service = RestServices.getServiceForEntity(id.getObjectType());
		if (service == null) {
			LOG.warn("No RestService has been definied for type: " + id.getObjectType() + ", identifier could not be serialized");
			return null;
		}
		
		IContext c= Core.createSystemContext();
		if (service.identifierInConstraint(c, id)) {
			IMendixObject obj = Core.retrieveId(c, id); //TODO: inefficient, especially for refsets, use retrieveIds?
			if (obj == null) {
				LOG.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
				return null;
			}
			return service.getObjecturl(c, obj);
		}
		return null;
	}
	
	public static PublishedService getServiceForEntity(String entityType) {
		if (servicesByEntity.containsKey(entityType))
			return servicesByEntity.get(entityType);
		
		//if not look into super entitites as well!
		IMetaObject meta = Core.getMetaObject(entityType);
		if (meta.getSuperObject() != null) {
			PublishedService superService = getServiceForEntity(meta.getSuperName());
			if (superService != null) {
				servicesByEntity.put(entityType, superService);
				return superService;
			}
		}
		servicesByEntity.put(entityType, null); //no service. Remember that
		return null;
	}

	static final ILogNode LOG = Core.getLogger("RestPublisher");
	static Map<String, PublishedService> services = new HashMap<String, PublishedService>();
	static Map<String, PublishedService> servicesByEntity = new HashMap<String, PublishedService>();
}
