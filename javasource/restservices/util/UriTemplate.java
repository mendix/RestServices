package restservices.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class UriTemplate {

	private static final String PARAMNAME_REGEX = "\\{([a-zA-Z_0-9]+)\\}";
	
	// http://tools.ietf.org/html/rfc3986#section-2.2
	private static final String QUERYPARAM_REGEX = "([^:/?#\\[\\]@!$&'()*+,;=]+?)";
	Pattern regex;
	List<String> paramNames = Lists.newArrayList();
	private String pathString;
	
	public UriTemplate(String pathString) {
		Preconditions.checkNotNull(pathString);
		
		this.pathString = pathString;
		String re = Pattern.quote(Utils.removeLeadingAndTrailingSlash(pathString));
		re = re.replaceAll("(^\\\\Q|\\\\E$)", "");
		re = "^\\/*" + re + "\\/*$";
		
		re = regexReplaceAll(re, PARAMNAME_REGEX, new Function<MatchResult, String>() {

			@Override
			public String apply(MatchResult match) {
				paramNames.add(match.group(1));
				return QUERYPARAM_REGEX;
			}
		});
		
		regex = Pattern.compile(re, Pattern.CASE_INSENSITIVE);
	}
	
	public boolean match(String uri, Map<String, String> params) {
		Preconditions.checkNotNull(uri);
		
		Matcher matcher = regex.matcher(uri);
		if (!matcher.matches())
			return false;
		
		for (int i = 0; i < matcher.groupCount(); i++) {
			params.put(paramNames.get(i), Utils.urlDecode(matcher.group(i + 1)));
		}
		return true;
	}
	
	//From community commons StringUtils 4.3.3
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

	public List<String> getTemplateVariables() {
		return Collections.unmodifiableList(paramNames);
	}
	
	@Override
	public String toString() {
		return String.format("%s[path=%s]", this.getClass().getSimpleName(), pathString);
	}

	public String createURI(final Map<String, String> values) {
		Preconditions.checkNotNull(values);
		
		Preconditions.checkArgument(values.keySet().equals(new HashSet<String>(paramNames)), "Incomplete set of values for path " + pathString + ", expected the following keys: " + paramNames + ", found: " + values.keySet());
		
		return regexReplaceAll(pathString, PARAMNAME_REGEX,  new Function<MatchResult, String>() {

			@Override
			public String apply(MatchResult match) {
				String paramName = match.group(1);
				String value = values.get(paramName);
				if (value == null || value.isEmpty()) {
					throw new IllegalArgumentException("No value was defined for path element '{" + paramName + "}'. The value should be non-empty.");
				}
				return Utils.urlEncode(values.get(paramName));
			}
		});
	}
}
