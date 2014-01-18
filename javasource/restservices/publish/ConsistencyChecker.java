package restservices.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.CoreRuntimeException;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

import restservices.RestServices;
import restservices.proxies.ServiceDefinition;
import restservices.util.Utils;

public class ConsistencyChecker {
	public static String check(ServiceDefinition def) {
		List<String> errors = new ArrayList<String>();
		
		if (!Utils.isValidKey(def.getName()))
			errors.add("Invalid service name");
		
		checkSource(def, errors);
		
		if (def.getEnableChangeTracking() && def.getEnableGet())
			checkOnPublishMf(def, errors);

		if (def.getEnableListing() && !def.getEnableGet())
			errors.add("Listing requires get to be enabled");
		
		if (def.getEnableCreate() || def.getEnableUpdate())
			checkOnUpdateMF(def, errors);
		
		if (def.getEnableDelete() && Utils.isNotEmpty(def.getOnDeleteMicroflow())) //Delete microflow is optional
			checkOnDeleteMF(def, errors);
		
		//TODO: should only one service that defines 'GET object', which will be the default
		
		//TODO: check security
		
		return errors.size() == 0 ? null : "* " + StringUtils.join(errors, "\n* ");
	}

	private static void checkOnDeleteMF(ServiceDefinition def,
			List<String> errors) {
		// TODO Auto-generated method stub
		
	}

	private static void checkOnUpdateMF(ServiceDefinition def,
			List<String> errors) {
		try {
			PublishedService.extractArgInfoForUpdateMicroflow(def);
		}
		catch(Exception e) {
			errors.add("Invalid update microflow: " + e.getMessage());
		}
	}

	private static void checkOnPublishMf(ServiceDefinition def,
			List<String> errors) {
		if (Core.getInputParameters(def.getOnPublishMicroflow()) == null)
			errors.add("OnPublishMicroflow is not a valid microflow");
		else {
			Map<String, String> args = Utils.getArgumentTypes(def.getOnPublishMicroflow());
			if (args.size() != 1)
				errors.add("OnPublishMicroflow should have exact one argument");
			if (!args.get(args.keySet().iterator().next()).equals(def.getSourceEntity()))
				errors.add("OnPublishMicroflow argument type should be " + def.getSourceEntity());
			
			IDataType resType = Core.getReturnType(def.getOnPublishMicroflow());
			if (!resType.isMendixObject() || resType.isList() || Core.getMetaObject(resType.getObjectType()).isPersistable())
				errors.add("OnPublishMicroflow should return a transient object");
		}
	}

	private static void checkSource(ServiceDefinition def, List<String> errors) {
		if (Core.getMetaObject(def.getSourceEntity()) == null)
			errors.add("Invalid source entity");
		else {
			if (!Core.getMetaObject(def.getSourceEntity()).isPersistable())
				errors.add("Source object should be a transient object");
			
			IMetaPrimitive prim = Core.getMetaObject(def.getSourceEntity()).getMetaPrimitive(def.getSourceKeyAttribute());
			if (prim == null)
				errors.add("Key attribute does not exist");
			
			if (def.getSourceConstraint() != null) {
				if (def.getSourceConstraint().contains(RestServices.CURRENTUSER_TOKEN)) {
					if (def.getEnableChangeTracking())
						errors.add("The source constrained is not allowed to refer to the current user if change tracking is enabled");
					if ("*".equals(def.getAccessRole()))
						errors.add("The source constrained is not allowed to refer to the current user if the service is world-readable or world-writable");
				}
				
				String xpath = "//" + def.getSourceEntity() + def.getSourceConstraint();
				xpath = xpath.replace(RestServices.CURRENTUSER_TOKEN, "empty");
				
				try {
					Core.retrieveXPathQuery(Core.createSystemContext(), xpath, 1, 0, ImmutableMap.of("id", "ASC"));
				} catch (CoreRuntimeException e) {
					errors.add("Constraint is not a valid xpath query: " + ExceptionUtils.getRootCauseMessage(e));
				} catch (CoreException e) {
					errors.add("Failed to verify validity of constraint: " + e.getMessage());
				}
			}
		}
	}
}
