/*
 * Modified 2008-01-07 to add role mapping from shibboleth
 * attribute (role) to confluence group membership.
 * Copyright [2008] [Macquarie University - MELCOE - MAMS]
 *
 * Modified 2007-05-21 from Georgetown version so it would strip @duke.edu
 * from user id and changed package to duke.
 * Copyright [2007] [Duke University]
 * modification of original edu.georgetown.middleware.confluence version:
 * Copyright [2006] [Georgetown University]
 * Original version can be found here:
 * https://svn.middleware.georgetown.edu/confluence/remoteAuthn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//Note: the original version of the Confluence 2.5+  modification was in duke
//package, not Georgetown
//TODO: modify package to reflect genericity
package edu.georgetown.middleware.confluence;

//~--- JDK imports ------------------------------------------------------------

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

//~--- non-JDK imports --------------------------------------------------------

import com.atlassian.confluence.user.ConfluenceAuthenticator;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.seraph.config.SecurityConfig;
import com.atlassian.user.Group;
import com.atlassian.user.User;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * An authenticator that uses the REMOTE_USER header as proof of authentication.
 * <p/>
 * Configuration properties are looked for in
 * <i>/remoteUserAuthenticator.properties</i> on the classpath. This file
 * may contain the following properties:
 * <ul>
 * <li><strong>create.users</strong> - Indicates whether accounts should be
 * created for individuals the first they are encountered
 * (acceptable values: true/false)</li>
 * <li><strong>update.info</strong> - Indicates whether existing accounts
 * should have their name and email address information
 * updated when the user logs in (acceptable values: true/false)</li>
 * <li><strong>default.roles</strong> - The default roles newly created
 * accounts will be given (format: comma seperated list)</li>
 * <li><strong>header.fullname</strong> - The name of the HTTP header that
 * will carry the full name of the user</li>
 * <li><strong>header.email</strong> - The name of the HTTP header that will
 * carry the email address for the user</li>
 *
 * <li><strong>update.roles</strong> - Indicates whether the existing accounts
 * should have their roles updated based on the header information. note: old
 * roles are not removed if the header doesn't contain it. (Acceptable values:
 * true/false. Default to false)</li>
 * <li><strong>header.dynamicroles.attributenames</strong> - The name of the
 * HTTP header that will carry the attribute name as indication of user's roles
 * (i.e. SHIB_EP_ENTITLEMENT). Case insensitive. Names separated by comma or
 * semicolon or space. If this entry is empty or not existing, then no dynamic
 * role mapping loaded</li>
 * <li><strong>header.dynamicroles.attributeValue1</strong> - The incoming
 * attribute value (from header.dynamicroles.attributenames headers) to be
 * mapped to group membership within confluence. See examples in properties
 * file for details.</li>
 * </ul>
 */
public class RemoteUserAuthenticator extends ConfluenceAuthenticator {

    //~--- static fields ------------------------------------------------------

    private final static Properties CONFIG_PROPERTIES;

    /**
     * create.user init parameter name
     */
    private final static String CREATE_USERS = "create.users";

    /**
     * default.role init parameter name
     */
    private final static String DEFAULT_ROLES = "default.roles";

    /**
     * Name of email address header property
     */
    private final static String EMAIL_HEADER_NAME_PROPERTY = "header.email";

    /**
     * Name of full name header property
     */
    private final static String FULLNAME_HEADER_NAME_PROPERTY =
        "header.fullname";

    /**
     * Location of configuration file on classpath
     */
    private final static String PROPERTY_FILE =
        "/remoteUserAuthenticator.properties";

    /**
     * Name of list of attributes (separated by comma or semicolon) in the
     * header as indication of dynamic roles to be used
     */
    private final static String ROLES_ATTRIB_NAMES =
        "header.dynamicroles.attributenames";

    /**
     * Prefix to be used for mapping of different roles. i.e.
     * header.dynamicroles.fromvalue=toRoleValue
     */
    private final static String ROLES_ATTRIB_PREFIX = "header.dynamicroles.";

    /** separator used in in reading roles */
    private final static String SEPARATOR = "[,; ]";

    /**
     * update.info init parameter name
     */
    private final static String UPDATE_INFO = "update.info";

    /** update.roles init parameter name */
    private final static String UPDATE_ROLES = "update.roles";

    /**
     * Set of header names to be watchfull for dynamic roles
     * (from PROPERTY_FILE. set in static block)
     */
    private static Set attribHeaders;

    /**
     * Whether to create accounts for new users or not
     * (from PROPERTY_FILE. set in static block)
     */
    private static boolean createUsers;

    /**
     * Default roles for newly created users
     * (from PROPERTY_FILE. set in static block)
     */
    private static List defaultRoles;

    /**
     * HTTP Header name that contains a user's email address
     * (from PROPERTY_FILE. set in static block)
     */
    private static String emailHeaderName;

    /**
     * HTTP Header name that contains a user's full name
     * (from PROPERTY_FILE. set in static block)
     */
    private static String fullNameHeaderName;

    /**
     * Remember the role mapping
     */
    private static Map mapRole = new HashMap(10);

    /**
     * Serial version UID
     */
    private static final long serialVersionUID = -5608187140008286795L;

    /**
     * Logger
     */
    private final static Log log =
        LogFactory.getLog(RemoteUserAuthenticator.class);

    /**
     * Whether or not to update name/email info for previously created users
     * (from PROPERTY_FILE. set in static block)
     */
    private static boolean updateInfo;

    /**
     * Whether to update roles for new users or not
     * (from PROPERTY_FILE. set in static block)
     */
    private static boolean updateRoles;

    //~--- static initializers ------------------------------------------------

    /**
     * Initialize properties from property file
     */
    static {
        if (log.isDebugEnabled()) {
            log.debug("Initializing authenticator using property file "
                      + PROPERTY_FILE);
        }

        InputStream propsIn =
            RemoteUserAuthenticator.class.getResourceAsStream(PROPERTY_FILE);

        CONFIG_PROPERTIES = new Properties();

        try {
            CONFIG_PROPERTIES.load(propsIn);

            // Load create users property
            createUsers = Boolean.valueOf(
                CONFIG_PROPERTIES.getProperty(CREATE_USERS)).booleanValue();

            if (log.isDebugEnabled()) {
                log.debug("Setting create new users to " + createUsers);
            }

            // Load udpate info property
            updateInfo = Boolean.valueOf(
                CONFIG_PROPERTIES.getProperty(UPDATE_INFO)).booleanValue();

            if (log.isDebugEnabled()) {
                log.debug("Setting update user information to " + updateInfo);
            }

            // Load update role property
            updateRoles = Boolean.valueOf(
                CONFIG_PROPERTIES.getProperty(UPDATE_ROLES)).booleanValue();

            if (log.isDebugEnabled()) {
                log.debug("Setting update user roles to " + updateRoles);
            }

            // Load default roles
            defaultRoles = new ArrayList();

            String roles = CONFIG_PROPERTIES.getProperty(DEFAULT_ROLES);

            if (roles != null) {
                roles = roles.trim();

                // depending on input from user, this could have empty strings
                defaultRoles.addAll(Arrays.asList(roles.split(SEPARATOR)));

                // clean up all empty roles, if exists
                for (Iterator roleIterator = defaultRoles.iterator();
                        roleIterator.hasNext(); ) {
                    String role = (String) roleIterator.next();

                    if (role.trim().length() == 0) {
                        roleIterator.remove();
                    }
                }

                if (log.isDebugEnabled()) {
                    for (Iterator it =
                            defaultRoles.iterator(); it.hasNext(); ) {
                        log.debug("Adding role " + it.next().toString()
                                  + " to list of default user roles");
                    }
                }
            }

            fullNameHeaderName =
                CONFIG_PROPERTIES.getProperty(FULLNAME_HEADER_NAME_PROPERTY);

            if (log.isDebugEnabled()) {
                log.debug(
                    "HTTP Header that may contain user's full name set to: "
                    + fullNameHeaderName);
            }

            emailHeaderName =
                CONFIG_PROPERTIES.getProperty(EMAIL_HEADER_NAME_PROPERTY);

            if (log.isDebugEnabled()) {
                log.debug(
                    "HTTP Header that may contain user's email address set to: "
                    + emailHeaderName);
            }

            // fill in the header names to be monitored
            attribHeaders = new HashSet();

            List attribNames = null;

            // Load dynamic roles property
            String attribNameStr =
                CONFIG_PROPERTIES.getProperty(ROLES_ATTRIB_NAMES);

            if (attribNameStr != null) {
                attribNames = Arrays.asList(attribNameStr.split(SEPARATOR));

                if (log.isDebugEnabled()) {
                    for (Iterator it = attribNames.iterator(); it.hasNext(); ) {
                        String attrib =
                            it.next().toString().trim().toLowerCase();

                        if (attrib.length() == 0) {
                            continue;
                        }

                        log.debug("Reading dynamic attribute: " + attrib);
                        attribHeaders.add(attrib);
                    }
                }
            }

            // remember the map from incoming attribute to confluence's group
            for (Enumeration propEnum = CONFIG_PROPERTIES.propertyNames();
                    propEnum.hasMoreElements(); ) {
                String prop = propEnum.nextElement().toString();

                // register as lower case in the map (dont think there would be
                // conflict between upper/lower cases
                String shibAttribFromConfig = prop.trim().toLowerCase();

                if (shibAttribFromConfig.startsWith(ROLES_ATTRIB_PREFIX)
                        &&!shibAttribFromConfig.startsWith(
                            ROLES_ATTRIB_NAMES)) {
                    String roleStr = CONFIG_PROPERTIES.getProperty(prop);
                    String roleKey = shibAttribFromConfig.substring(ROLES_ATTRIB_PREFIX.length());

                    //this is the map from shib_key = conf_group1, group2, etc
                    mapRole.put(roleKey,
                                Arrays.asList(roleStr.trim().split(SEPARATOR)));

                    if (log.isDebugEnabled()) {
                        log.debug("Found role mapping declared as " + prop);
                    }
                }
            }
        } catch (IOException e) {
            log.warn(
                "Unable to read properties file, using default properties", e);
        }
    }

    //~--- methods ------------------------------------------------------------

    /**
     * Assigns a user to the roles.
     *
     * @param user the user to assign to the roles.
     */
    private void assignUserToRoles(User user, Collection roles) {
        if (roles.size() == 0) {
            log.debug("No roles specified, not adding any roles...");
        } else {
            UserAccessor userAccessor = getUserAccessor();

            if (log.isDebugEnabled()) {
                log.debug("Assigning roles to user " + user.getName());
            }

            String role;
            Group  group;

            for (Iterator it = roles.iterator(); it.hasNext(); ) {
                role = it.next().toString().trim();

                if (role.length() == 0) {
                    continue;
                }

                if (log.isDebugEnabled()) {
                    log.debug("Assigning " + user.getName() + " to role "
                              + role);
                }

                try {
                    group = userAccessor.getGroup(role);
                    userAccessor.addMembership(group, user);
                } catch (Throwable e) {
                    log.error("Attempted to add user " + user + " to role "
                              + role + " but the role does not exist.");
                }
            }
        }
    }

    /**
     * Change userid to lower case.
     *
     * @param userid userid to be changed
     * @return lower case version of it
     */
    private String convertUsername(String userid) {
        if (userid != null) {
            userid = userid.toLowerCase();
        }

        return userid;
    }

    /**
     * Creates a new user if the configuration allows it.
     *
     * @param userid user name for the new user
     * @return the new user
     */
    private Principal createUser(String userid) {
        UserAccessor userAccessor = getUserAccessor();
        Principal    user         = null;

        if (createUsers) {
            if (log.isInfoEnabled()) {
                log.info("Creating user account for " + userid);
            }

            try {
                user = userAccessor.createUser(userid);
            } catch (Throwable t) {

                // Note: just catching EntityException like we used to do didn't
                // seem to cover Confluence massive with Oracle
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Error creating user " + userid
                        + ". Will ignore and try to get the user (maybe it was already created)", t);
                }

                user = getUser(userid);

                if (user == null) {
                    log.error(
                        "Error creating user " + userid
                        + ". Got null user after attempted to create user (so it probably was not a duplicate).", t);
                }
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug(
                    "Configuration does NOT allow for creation of new user accounts, authentication will fail for "
                    + userid);
            }
        }

        return user;
    }

    /**
     * Initialize properties
     *
     * @param params
     * @param config
     */
    public void init(Map params, SecurityConfig config) {
        super.init(params, config);
    }

    private void updateUser(Principal user, String fullName,
                            String emailAddress) {
        UserAccessor userAccessor = getUserAccessor();

        // If we have new values for name or email, update the user object
        if ((user != null) && (user instanceof User)) {
            User    userToUpdate = (User) user;
            boolean updated      = false;

            if ((fullName != null)
                    &&!fullName.equals(userToUpdate.getFullName())) {
                if (log.isDebugEnabled()) {
                    log.debug("updating user fullName to '" + fullName + "'");
                }

                userToUpdate.setFullName(fullName);
                updated = true;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("new user fullName is same as old one: '"
                              + fullName + "'");
                }
            }

            if ((emailAddress != null)
                    &&!emailAddress.equals(userToUpdate.getEmail())) {
                if (log.isDebugEnabled()) {
                    log.debug("updating user emailAddress to '" + emailAddress
                              + "'");
                }

                userToUpdate.setEmail(emailAddress);
                updated = true;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("new user emailAddress is same as old one: '"
                              + emailAddress + "'");
                }
            }

            if (updated) {
                try {
                    userAccessor.saveUser(userToUpdate);
                } catch (Throwable t) {
                    log.error("Couldn't update user " + userToUpdate.getName(),
                              t);
                }
            }
        }
    }

    //~--- get methods --------------------------------------------------------

    private String getEmailAddress(HttpServletRequest request) {
        String emailAddress = request.getHeader(emailHeaderName);

        log.debug("Got emailAddress '" + emailAddress + "' for header '"
                  + emailHeaderName + "'");

        if ((emailAddress != null) && (emailAddress.length() > 0)) {
            emailAddress = emailAddress.toLowerCase();
        }

        return emailAddress;
    }

    private String getFullName(HttpServletRequest request, String userid) {
        String fullName = request.getHeader(fullNameHeaderName);

        log.debug("Got fullName '" + fullName + "' for header '"
                  + fullNameHeaderName + "'");

        if ((fullName == null) || (fullName.length() == 0)) {
            fullName = userid;
        }

        return fullName;
    }

    private Collection getRolesFromHeader(HttpServletRequest request) {

        // check if we're interested in some headers
        if (attribHeaders.isEmpty()) {
            return Collections.emptyList();
        }

        // effective roles as in presented in headers if it existed in
        // mapRoleNames
        Set dynamicRoles = new HashSet();

        for (Enumeration en =
                request.getHeaderNames(); en.hasMoreElements(); ) {
            String header     = en.nextElement().toString();
            String trimHeader = header.trim().toLowerCase();

            if (log.isDebugEnabled()) {
                log.debug("Analyzing header \"" + header
                          + "\" for a mapped role = "
                          + request.getHeader(header));
            }

            // see if this header is something we'd be interested in
            if (attribHeaders.contains(trimHeader)) {
                String[] roles = request.getHeader(header).split(SEPARATOR);

                for (int i = 0; i < roles.length; i++) {
                    String role = roles[i].trim().toLowerCase();

                    if (role.length() == 0) {
                        continue;
                    }

                    List confluenceGroups = (List) mapRole.get(role);

                    if (confluenceGroups != null) {
                        dynamicRoles.addAll(confluenceGroups);

                        if (log.isDebugEnabled()) {
                            String confRoles = "";
                            for(int j=confluenceGroups.size()-1;j>-1;j--){
                                confRoles += confluenceGroups.get(j).toString();
                                if(j != 0) confRoles += ",";
                            }
                            log.debug("Mapping role \"" + role + "\" to \""
                                      + confRoles + "\"");
                        }
                    }
                }
            }
        }

        //clean up a bit, in case these came into the list
        dynamicRoles.remove(null);
        dynamicRoles.remove("");

        return dynamicRoles;
    }

    // Tried overriding login(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse), but
    // it doesn't get called at all. That sucks because this method can often be called > 20 times per page.

    /**
     * @see com.atlassian.seraph.auth.Authenticator#getUser(
     *      javax.servlet.http.HttpServletRequest,
     *      javax.servlet.http.HttpServletResponse)
     *
     * @param request
     * @param response
     *
     * @return
     */
    public Principal getUser(HttpServletRequest request,
                             HttpServletResponse response) {
        if (log.isDebugEnabled()) {
            log.debug("Request made to " + request.getRequestURL()
                      + " triggered this AuthN check");
        }

        HttpSession httpSession = request.getSession();
        Principal   user;

        // Check if the user is already logged in
        if ((httpSession != null)
                && (httpSession.getAttribute(
                    ConfluenceAuthenticator.LOGGED_IN_KEY) != null)) {
            user = (Principal) httpSession.getAttribute(
                ConfluenceAuthenticator.LOGGED_IN_KEY);

            if (log.isDebugEnabled()) {
                log.debug(user.getName() + " already logged in, returning.");
            }

            return user;
        }

        // Since they aren't logged in, get the user name from
        // the REMOTE_USER header
        String userid = request.getRemoteUser();

        if ((userid == null) || (userid.length() <= 0)) {
            if (log.isDebugEnabled()) {
                log.debug(
                    "Remote user was null or empty, can not perform authentication");
            }

            return null;
        }

        // Convert username to all lowercase
        userid = convertUsername(userid);

        // Pull name and address from headers
        String fullName     = getFullName(request, userid);
        String emailAddress = getEmailAddress(request);

        // Try to get the user's account based on the user name
        user = getUser(userid);

        boolean newUser = false;

        // User didn't exist or was problem getting it. we'll try to create it
        // if we can, otherwise will try to get it again.
        if (user == null) {
            user = createUser(userid);

            if (user != null) {
                newUser = true;
                updateUser(user, fullName, emailAddress);
            }
        } else {
            if (updateInfo) {
                updateUser(user, fullName, emailAddress);
            }
        }

        if (updateRoles || newUser) {
            assignUserToRoles((User) user, defaultRoles);
            assignUserToRoles((User) user, getRolesFromHeader(request));
        }

        // Now that we have the user's account, add it to the session and return
        if (log.isDebugEnabled()) {
            log.debug("Logging in user " + user.getName());
        }

        request.getSession().setAttribute(
            ConfluenceAuthenticator.LOGGED_IN_KEY, user);
        request.getSession().setAttribute(
            ConfluenceAuthenticator.LOGGED_OUT_KEY, null);

        return user;
    }

    /**
     * {@inheritDoc}
     *
     * @param userid
     *
     * @return
     */
    public Principal getUser(String userid) {
        if (log.isDebugEnabled()) {
            log.debug("Getting user " + userid);
        }

        UserAccessor userAccessor = getUserAccessor();
        Principal    user         = null;

        try {
            user = userAccessor.getUser(userid);

            if (user == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No user account exists for " + userid);
                }
            }
        } catch (Throwable t) {
            log.error("Error getting user", t);
        }

        return user;
    }
}
