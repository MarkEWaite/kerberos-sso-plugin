/*
 *  The MIT License
 *
 *  Copyright (c) 2014 Sony Mobile Communications Inc. All rights reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package com.sonymobile.jenkins.plugins.kerberossso;

import com.sonymobile.jenkins.plugins.kerberossso.ioc.SpnegoKerberosAuthenticationFactory;
import hudson.Extension;
import hudson.Plugin;
import hudson.model.Descriptor;
import hudson.util.PluginServletFilter;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.codelibs.spnego.SpnegoHttpFilter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core of this Plugin. Handles the configuration of the {@link KerberosSSOFilter}
 * It also starts / stops the filter at the user's request and data-binds to config.groovy.
 * @author Joakim Ahle &lt;joakim.ahle@sonymobile.com&gt;
 */
@Extension
public class PluginImpl extends Plugin {

    private static final Logger logger = Logger.getLogger(PluginImpl.class.getName());

    private boolean enabled = false;

    private String accountName = "Service account";
    private Secret password;
    private boolean redirectEnabled = false;
    private String redirect = "yourdomain.com";

    private String krb5Location = "/etc/krb5.conf";
    private String loginLocation = "/etc/login.conf";
    private String loginServerModule = "spnego-server";
    private String loginClientModule = "spnego-client";

    private boolean anonymousAccess = false;
    private boolean allowLocalhost = true;
    private boolean allowBasic = true;
    private boolean allowDelegation = false;
    private boolean allowUnsecureBasic = true;
    private boolean promptNtlm = false;

    private transient KerberosSSOFilter filter;

    /**
     * Fetches the singleton instance of this plugin.
     * @return the instance.
     */
    public static PluginImpl getInstance() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins != null) {
            return jenkins.getPlugin(PluginImpl.class);
        } else {
            return null;
        }
    }

    /**
     * Get the filter instance,
     * @return The filter instance.
     */
    /*package for testing*/ @CheckForNull KerberosSSOFilter getFilter() {
        return filter;
    }

    /**
     * Starts the plugin. Loads previous configuration if such exists.
     * @throws Exception if the Kerberos filter cannot be added to Jenkins.
     */
    @Override
    public void start() throws Exception {
        load();
        try {
            if (enabled) {
                registerFilter();
            }
        } catch (ServletException e) {
            logger.log(Level.SEVERE, "Failed initialize plugin due to faulty config.", e);
            enabled = false;
            removeFilter();
        }
    }

    /**
     * Stops this plugin and removes the filter from Jenkins.
     * @throws Exception if removing the filter fails.
     */
    @Override
    public void stop() throws Exception {
        removeFilter();
    }

    /**
     * Safe and complete removal of the filter from the system.
     * @throws ServletException if
     */
    private void removeFilter() throws ServletException {
        if (filter != null) {
            PluginServletFilter.removeFilter(filter);
            filter.destroy();
            filter = null;
        }
    }

    /**
     * Create and attach the filter.
     * @throws ServletException Unable to add filter.
     */
    private void registerFilter() throws ServletException {
        this.filter = new KerberosSSOFilter(createConfigMap(), new SpnegoKerberosAuthenticationFactory());
        PluginServletFilter.addFilter(filter);
    }

    /**
     * When submit is pressed on the global config page and any settings for this plugin are changed,
     * this method is called. It updates all the fields, restarts or stops the filter depending on configuration
     * and saves the configuration to disk.
     * @param req the Stapler Request to serve.
     * @param formData the JSON data containing the new configuration.
     * @throws Descriptor.FormException if any data in the form is wrong.
     * @throws IOException when adding and removing the filter.
     * @throws ServletException when the filter is created faulty config.
     */
    @Override
    public void configure(StaplerRequest req, JSONObject formData)
            throws Descriptor.FormException, IOException, ServletException {

        if (formData.has("enabled")) {

            JSONObject data = (JSONObject)formData.get("enabled");

            if (!data.has("account") || !data.has("password") || !data.has("krb5Location")
                    || !data.has("loginLocation") || !data.has("loginServerModule")
                    || !data.has("loginClientModule") || !data.has("anonymousAccess")
                    || !data.has("allowLocalhost") || !data.has("allowBasic")
                    || !data.has("allowDelegation") || !data.has("promptNtlm")
                    || !data.has("allowUnsecureBasic")) {

                throw new Descriptor.FormException("Malformed form received. Try again.", "enabled");
            }

            // Starting with data that needs validation to not break an existing configuration.

            changeLoginLocation((String)data.get("loginLocation"));

            if (data.has("redirectEnabled")) {
                JSONObject redirectData = (JSONObject)data.get("redirectEnabled");
                if (redirectData.has("redirect")) {
                    this.redirectEnabled = true;
                    String domain = (String)redirectData.get("redirect");
                    if (!domain.isEmpty()) {
                        this.redirect = domain;
                    } else {
                        throw new Descriptor.FormException("Cannot specify empty domain", "redirect");
                    }
                }
            } else {
                this.redirectEnabled = false;
            }

            //Then processing data that it's up to the user to get correct.

            this.enabled = true;

            this.accountName = (String)data.get("account");

            this.password = Secret.fromString((String)data.get("password"));


            this.krb5Location = (String)data.get("krb5Location");

            this.loginServerModule = (String)data.get("loginServerModule");
            this.loginClientModule = (String)data.get("loginClientModule");
            this.anonymousAccess = (Boolean)data.get("anonymousAccess");
            this.allowLocalhost = (Boolean)data.get("allowLocalhost");
            this.allowBasic = (Boolean)data.get("allowBasic");
            this.allowDelegation = (Boolean)data.get("allowDelegation");
            this.promptNtlm = (Boolean)data.get("promptNtlm");
            this.allowUnsecureBasic = (Boolean)data.get("allowUnsecureBasic");

            removeFilter();
            registerFilter();
        } else {
            removeFilter();
            enabled = false;
        }

        save();
    }

    /**
     * Set enabled config parameter.
     * @param enabled value of enabled
     */
    @Restricted(NoExternalUse.class)
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Set accountName config parameter.
     * @param accountName value of accountName
     */
    @Restricted(NoExternalUse.class)
    void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    /**
     * Set password config parameter.
     * @param password value of password
     */
    @Restricted(NoExternalUse.class)
    void setPassword(Secret password) {
        this.password = password;
    }

    /**
     * Set redirectEnabled config parameter.
     * @param redirectEnabled value of redirectEnabled
     */
    @Restricted(NoExternalUse.class)
    void setRedirectEnabled(boolean redirectEnabled) {
        this.redirectEnabled = redirectEnabled;
    }

    /**
     * Set redirect config parameter.
     * @param redirect value of redirect
     */
    @Restricted(NoExternalUse.class)
    void setRedirect(String redirect) {
        this.redirect = redirect;
    }

    /**
     * Set krb5Location config parameter.
     * @param krb5Location value of krb5Location
     */
    @Restricted(NoExternalUse.class)
    void setKrb5Location(String krb5Location) {
        this.krb5Location = krb5Location;
    }

    /**
     * Set loginLocation config parameter.
     * @param loginLocation value of loginLocation
     */
    @Restricted(NoExternalUse.class)
    void setLoginLocation(String loginLocation) {
        this.loginLocation = loginLocation;
    }

    /**
     * Set loginServerModule config parameter.
     * @param loginServerModule value of loginServerModule
     */
    @Restricted(NoExternalUse.class)
    void setLoginServerModule(String loginServerModule) {
        this.loginServerModule = loginServerModule;
    }

    /**
     * Set loginClientModule config parameter.
     * @param loginClientModule value of loginClientModule
     */
    @Restricted(NoExternalUse.class)
    void setLoginClientModule(String loginClientModule) {
        this.loginClientModule = loginClientModule;
    }

    /**
     * Set allowLocalhost config parameter.
     * @param allowLocalhost value of allowLocalhost
     */
    @Restricted(NoExternalUse.class)
    void setAllowLocalhost(boolean allowLocalhost) {
        this.allowLocalhost = allowLocalhost;
    }

    /**
     * Set allowBasic config parameter.
     * @param allowBasic value of allowBasic
     */
    @Restricted(NoExternalUse.class)
    void setAllowBasic(boolean allowBasic) {
        this.allowBasic = allowBasic;
    }

    /**
     * Set allowDelegation config parameter.
     * @param allowDelegation value of allowDelegation
     */
    @Restricted(NoExternalUse.class)
    void setAllowDelegation(boolean allowDelegation) {
        this.allowDelegation = allowDelegation;
    }

    /**
     * Set allowUnsecureBasic config parameter.
     * @param allowUnsecureBasic value of allowUnsecureBasic
     */
    @Restricted(NoExternalUse.class)
    void setAllowUnsecureBasic(boolean allowUnsecureBasic) {
        this.allowUnsecureBasic = allowUnsecureBasic;
    }

    /**
     * Set promptNtlm config parameter.
     * @param promptNtlm value of promptNtlm
     */
    @Restricted(NoExternalUse.class)
    void setPromptNtlm(boolean promptNtlm) {
        this.promptNtlm = promptNtlm;
    }

    /**
     * Allows for reconfigure if config have been altered programatically.
     *
     * @throws ServletException if occurs
     */
    @Restricted(NoExternalUse.class)
    public void reconfigure() throws ServletException {
        removeFilter();
        registerFilter();
    }

    /**
     * Tests and changes the passed loginLocation
     * @param newLoginLocation the new location of login.conf
     * @throws Descriptor.FormException if the file does not exist
     */
    private void changeLoginLocation(String newLoginLocation) throws Descriptor.FormException {
        File login = new File(newLoginLocation);
        if (login.exists() && login.isFile()) {
            this.loginLocation = newLoginLocation;
        } else {
            throw new Descriptor.FormException("The path to login.conf is incorrect.", "loginLocation");
        }
    }

    /**
     * Used by groovy for data-binding.
     * @return whether the Filter is currently enabled or not.
     */
    @Restricted(NoExternalUse.class)
    public boolean getEnabled() {
        return enabled;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current service / pre-auth account.
     */
    @Restricted(NoExternalUse.class)
    public String getAccountName() {
        return accountName;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current service / pre-auth password as a secret.
     */
    @Restricted(NoExternalUse.class)
    public Secret getPassword() {
        return password;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether the user has checked domain redirection or not.
     */
    @Restricted(NoExternalUse.class)
    public boolean isRedirectEnabled() {
        return redirectEnabled;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current domain to redirect to, if redirect is enabled.
     */
    @Restricted(NoExternalUse.class)
    public String getRedirect() {
        return redirect;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current location of the krb5.conf file.
     */
    @Restricted(NoExternalUse.class)
    public String getKrb5Location() {
        return krb5Location;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current location of the login.conf file.
     */
    @Restricted(NoExternalUse.class)
    public String getLoginLocation() {
        return loginLocation;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current Login-server module.
     */
    @Restricted(NoExternalUse.class)
    public String getLoginServerModule() {
        return loginServerModule;
    }

    /**
     * Used by groovy for data-binding.
     * @return the current Login-client module.
     */
    @Restricted(NoExternalUse.class)
    public String getLoginClientModule() {
        return loginClientModule;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether the user needs to authenticate on non-login URLs.
     */
    @Restricted(NoExternalUse.class)
    public boolean getAnonymousAccess() {
        return anonymousAccess;
    }

    /**
     * Set login all URLs.
     * @param anonymousAccess Permit anonymous access.
     */
    @Restricted(NoExternalUse.class)
    public void setAnonymousAccess(boolean anonymousAccess) {
        this.anonymousAccess = anonymousAccess;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether Localhost should be allowed without authentication or not.
     */
    @Restricted(NoExternalUse.class)
    public boolean isAllowLocalhost() {
        return allowLocalhost;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether unsecure basic should be used if Kerberos fails.
     */
    @Restricted(NoExternalUse.class)
    public boolean isAllowUnsecureBasic() {
        return allowUnsecureBasic;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether NTLM users should be prompted to use basic authentication.
     */
    @Restricted(NoExternalUse.class)
    public boolean isPromptNtlm() {
        return promptNtlm;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether servlet delegation should be used.
     */
    @Restricted(NoExternalUse.class)
    public boolean isAllowDelegation() {
        return allowDelegation;
    }

    /**
     * Used by groovy for data-binding.
     * @return whether Basic authentication should be used if Kerberos fails.
     */
    @Restricted(NoExternalUse.class)
    public boolean isAllowBasic() {
        return allowBasic;
    }

    /**
     * Used to determine if Jenkins have to be restarted for the config changes to take place.
     * @return whether Jenkins has to be restarted.
     * @deprecated Unused
     */
    @Deprecated
    public boolean isRestartNeeded() {
        return filter != null;
    }

    /**
     * Creates a map of the current configuration. This is then sent to the Kerberos filter's constructor.
     * @return a mappning between properties and configuration.
     */
    private Map<String, String> createConfigMap() {
        Map<String, String> config = new HashMap<String, String>();
        config.put(SpnegoHttpFilter.Constants.ALLOW_BASIC, String.valueOf(allowBasic));
        config.put(SpnegoHttpFilter.Constants.KRB5_CONF, krb5Location);
        config.put(SpnegoHttpFilter.Constants.LOGIN_CONF, "file:" + loginLocation);
        config.put(SpnegoHttpFilter.Constants.ALLOW_LOCALHOST, String.valueOf(allowLocalhost));
        config.put(SpnegoHttpFilter.Constants.ALLOW_DELEGATION, String.valueOf(allowDelegation));
        config.put(SpnegoHttpFilter.Constants.ALLOW_UNSEC_BASIC, String.valueOf(allowUnsecureBasic));
        config.put(SpnegoHttpFilter.Constants.PROMPT_NTLM, String.valueOf(promptNtlm));
        config.put(SpnegoHttpFilter.Constants.PREAUTH_USERNAME, accountName);
        config.put(SpnegoHttpFilter.Constants.PREAUTH_PASSWORD, password.getPlainText());
        config.put(SpnegoHttpFilter.Constants.SERVER_MODULE, loginServerModule);
        config.put(SpnegoHttpFilter.Constants.CLIENT_MODULE, loginClientModule);
        config.put("spnego.logger.level", 1 + "");

        return config;
    }
}
