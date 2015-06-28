package restservices.publish;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.CoreRuntimeException;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IDataType.DataTypeEnum;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

import communitycommons.XPath;
import restservices.RestServices;
import restservices.proxies.DataServiceDefinition;
import restservices.util.Utils;
import system.proxies.User;
import system.proxies.UserRole;

public class ConsistencyChecker {
	public static String check(DataServiceDefinition def) {
		List<String> errors = new ArrayList<String>();
		
		if (!Utils.isValidKey(def.getName()))
			errors.add("Invalid service name");
		
		if (!def.getName().toLowerCase().equals(def.getName()))
			errors.add("Service name should be lowercased");
		
		checkSource(def, errors);
		
		if (def.getEnableChangeLog() && def.getEnableGet())
			checkOnPublishMf(def, errors);

		if (def.getEnableListing() && !def.getEnableGet())
			errors.add("Listing requires get to be enabled");
		
		if (def.getEnableCreate() || def.getEnableUpdate())
			checkOnUpdateMF(def, errors);
		
		if (def.getEnableDelete() && Utils.isNotEmpty(def.getOnDeleteMicroflow())) //Delete microflow is optional
			checkOnDeleteMF(def, errors);
		
		//TODO: should only one service that defines 'GET object', which will be the default
		
		String secError = checkAccessRole(def.getAccessRole());
		if (secError != null)
			errors.add(secError);
		
		return errors.size() == 0 ? null : "* " + StringUtils.join(errors, "\n* ");
	}

	public static String checkAccessRole(String accessRole) {
		if (accessRole == null || accessRole.trim().isEmpty())
			return "No access role has been set. Use '*' for all, or provide a Userrole name or Microflow name";
		
		if ("*".equals(accessRole))
			return null;
		
		try {
			if (null != XPath.create(Core.createSystemContext(), UserRole.class).eq(UserRole.MemberNames.Name, accessRole).first())
				return null;
		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
		
		if (!Utils.microflowExists(accessRole))
			return "'" + accessRole + "' doesn't seem to be an existing userrole or microflow";
		
		if (Utils.getArgumentTypes(accessRole).size() != 0)
			return "The authentication microflow '" + accessRole + "' shouldn' t take any arguments";
		
		IDataType rt = Core.getReturnType(accessRole);
		if (rt.getType() != DataTypeEnum.Object || !Core.isSubClassOf(User.entityName, rt.getObjectType()))
			return "The authentication microflow '" + accessRole + "' should return a 'System.User' object or derivate thereof";
		
		return null;
	}

	private static void checkOnDeleteMF(DataServiceDefinition def,
			List<String> errors) {
		// TODO Auto-generated method stub
		
	}

	private static void checkOnUpdateMF(DataServiceDefinition def,
			List<String> errors) {
		try {
			DataService.extractArgInfoForUpdateMicroflow(def);
		}
		catch(Exception e) {
			errors.add("Invalid update microflow: " + e.getMessage());
		}
	}

	private static void checkOnPublishMf(DataServiceDefinition def,
			List<String> errors) {
		if (!Utils.microflowExists(def.getOnPublishMicroflow()))
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

	private static void checkSource(DataServiceDefinition def, List<String> errors) {
		if (def.getSourceEntity() == null || Core.getMetaObject(def.getSourceEntity()) == null)
			errors.add("Invalid source entity");
		else {
			if (!Core.getMetaObject(def.getSourceEntity()).isPersistable())
				errors.add("Source object should be a transient object");
			
			IMetaPrimitive prim = Core.getMetaObject(def.getSourceEntity()).getMetaPrimitive(def.getSourceKeyAttribute());
			if (prim == null)
				errors.add("Key attribute does not exist");
			
			if (def.getSourceConstraint() != null) {
				if (def.getSourceConstraint().contains(RestServices.CURRENTUSER_TOKEN)) {
					if (def.getEnableChangeLog())
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
