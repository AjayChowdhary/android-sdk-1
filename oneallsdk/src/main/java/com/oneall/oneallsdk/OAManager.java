package com.oneall.oneallsdk;

import com.oneall.oneallsdk.OAError.ErrorCode;
import com.oneall.oneallsdk.rest.ServiceCallback;
import com.oneall.oneallsdk.rest.ServiceManagerProvider;
import com.oneall.oneallsdk.rest.models.NativeLoginRequest;
import com.oneall.oneallsdk.rest.models.PostMessageRequest;
import com.oneall.oneallsdk.rest.models.PostMessageResponse;
import com.oneall.oneallsdk.rest.models.Provider;
import com.oneall.oneallsdk.rest.models.ResponseConnection;
import com.oneall.oneallsdk.rest.models.User;
import com.oneall.oneallsdk.rest.service.ConnectionService;
import com.oneall.oneallsdk.rest.service.MessagePostService;
import com.oneall.oneallsdk.rest.service.UserService;
import com.twitter.sdk.android.core.TwitterAuthConfig;
import com.twitter.sdk.android.core.TwitterCore;

import android.app.Activity;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.fabric.sdk.android.Fabric;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

/**
 * Main manager providing interface to all OneAll operations and the whole API
 */
public class OAManager {

    // region Constants

    private static final Integer INTENT_REQUEST_CODE_SELECT_ACTIVITY = 0;
    private static final Integer INTENT_REQUEST_CODE_LOGIN = 1;

    // endregion

    // region Helper classes and interfaces

    public interface LoginHandler {

        void loginSuccess(User user, Boolean newUser);

        void loginFailure(OAError error);
    }

    public interface OAManagerPostHandler {

        void postComplete(Boolean success, PostMessageResponse response);
    }

    // endregion

    // region Properties

    /** singleton instance variable */
    private static OAManager mInstance = null;

    /** application context */
    private Context mAppContext = null;

    /** login handler to call back */
    private LoginHandler loginHandler;

    /** nonce generated by the last login request */
    private String lastNonce;

    /** state flag, bad, bad, bad, see onPostResume() for explanation */
    private Boolean loginOnResume = false;

    /** key of the provider selected */
    private String loginOnResumeProvider;

    /** currently selected provider */
    private Provider selectedProvider;

    // endregion

    // region Lifecycle

    /**
     * gets a manager instance
     *
     * @return a OAManager instance
     */
    public static OAManager getInstance() {
        if (mInstance == null) {
            synchronized (OAManager.class) {
                if (mInstance == null) {
                    mInstance = new OAManager();
                }
            }
        }
        return mInstance;
    }

    /**
     * destroys the current instance and its dependencies,
     * opening them up for garbage collection
     */
    public static void destroyInstance() {
        synchronized (OAManager.class) {
            if (mInstance != null) {
                // clean up
                FacebookWrapper.destroyInstance();
                TwitterWrapper.destroyInstance();
                // allow instance to be GCed
                mInstance = null;
            }
        }
    }

    // endregion

    // region Interface methods

    /**
     * setup manager instance. should be called before using the manager. Otherwise the manager will
     * not function.
     *
     * @param context            context
     * @param subdomain          subdomain of your OneAll application
     * @param twitterConsumerKey (optional) Twitter consumer key from
     *                           {@link <a href="https://apps.twitter.com/">https://apps.twitter.com/</a>}
     * @param twitterSecret      (optional) Twitter secret key from
     *                           {@link <a href="https://apps.twitter.com/">https://apps.twitter.com/</a>}
     * @throws java.lang.NullPointerException     if {@code context} is null
     * @throws java.lang.IllegalArgumentException if {@code subdomain} is null or empty
     */
    public void setup(
            Context context,
            String subdomain,
            String twitterConsumerKey,
            String twitterSecret) {

        if (context == null) {
            throw new NullPointerException("context cannot be null");
        }

        if (subdomain == null || subdomain.trim().length() == 0) {
            throw new IllegalArgumentException("Subdomain cannot be empty");
        }

        // make sure the ref we hold is from the application context
        mAppContext = context.getApplicationContext();

        OALog.init(mAppContext);

        // if the parent app already initialized Fabric for some of its other modules
        // make sure it includes the required TwitterCore. Otherwise, init it ourselves
        if (!Fabric.isInitialized()) {
            TwitterAuthConfig authConfig = new TwitterAuthConfig(twitterConsumerKey, twitterSecret);
            Fabric.with(this.mAppContext, new TwitterCore(authConfig));
        } else {
            if (Fabric.getKit(TwitterCore.class) == null) {
                OALog.error("Twitter's Fabric is already initialized but it doesn't include TwitterCore kit which is" +
                        "required for Auth calls");
            } else {
                OALog.warn("Twitter's Fabric was already initialized with a TwitterCore kit. Reusing existing kit");
            }
        }

        OALog.info(String.format("SDK init with subdomain %s", subdomain));

        Settings.getInstance().setSubdomain(subdomain);
        ProviderManager.getInstance().refreshProviders(mAppContext);
    }

    /**
     * Starts authentication with OneAll using selected social network. If additional information is
     * required for provider, the user is shown a dialog with request for additional data. When all
     * the data is provided to the library, authentication process continues using OneAll API
     * servers. Upon completion {@code handler} will be used to inform the caller on operation
     * result. Information about the user is retrieved as part of the authentication process and
     * returned to {@code handler} callback.
     *
     * If the provider specified is "{@code facebook}" native device Facebook authentication is
     * used to login the user by using Facebook SDK for Android. If the provider specified is
     * "{@code twitter}" native device authentication is used to login the user with help of
     * Twitter SDK.
     *
     * @param activity current activity
     * @param provider provider to use for authentication; list of supported providers can be
     *                 retrieved using {@link #getProviders()}
     * @param handler  completion handler, will be used to inform the caller about the end of the
     *                 authentication (either success or failure)
     * @return {@code true} if the login process has started successfully, {@code false} otherwise;
     * in case of {@code false} return code, {@code handler} will not be called
     * @throws java.lang.IllegalStateException if the manager has not been initialized
     */
    public Boolean login(final Activity activity, String provider, LoginHandler handler) {
        validateInitialization();

        loginHandler = handler;
        selectedProvider = ProviderManager.getInstance().findByKey(provider);

        if (selectedProvider == null) {
            throw new IllegalArgumentException("Specified provider does not exist");
        }

        lastNonce = UUID.randomUUID().toString();

        switch (provider) {
            case "facebook":
                boolean res =
                        FacebookWrapper.getInstance().login(
                                activity,
                                new FacebookWrapper.SessionStateListener() {
                                    @Override
                                    public void success(String accessToken) {
                                        facebookLoginSuccess(activity, accessToken);
                                    }

                                    @Override
                                    public void failure(OAError error) {
                                        facebookLoginFailure(error);
                                    }
                                });

                if (!res) {
                    webLoginWithProvider(activity, selectedProvider);
                }
                break;
            case "twitter":
                TwitterWrapper.getInstance().login(activity, new TwitterWrapper.LoginComplete() {
                    @Override
                    public void success(String accessToken, String secret) {
                        twitterLoginSuccess(activity, accessToken, secret);
                    }

                    @Override
                    public void failure(OAError error) {
                        twitterLoginFailure(error);
                    }
                });
                break;
            default:
                webLoginWithProvider(activity, selectedProvider);
                break;
        }

        return true;
    }

    /**
     * Starts authentication with OneAll. In order to know which method to use for authentication
     * new activity is opened with selection of providers that are setup with current provider.
     * After the user selects one of the methods using this activity, the result is returned to
     * {@link #onActivityResult(int, int, android.content.Intent)} which, in turn, continues
     * authentication using selected social network. Upon completion {@code handler} will be used
     * to inform the caller on operation result. Information about the user is retrieved as part of
     * the authentication process and returned to {@code handler} callback.
     *
     * If the provider specified is "{@code facebook}" native device Facebook authentication is
     * used to login the user by using Facebook SDK for Android. If the provider specified is
     * "{@code twitter}" native device authentication is used to login the user with help of
     * Twitter SDK.
     *
     * @param activity current activity
     * @param handler  completion handler, will be used to inform the caller about the end of the
     *                 authentication (either success or failure)
     * @return {@code true} if the login process has started successfully, {@code false} otherwise;
     * in case of {@code false} return code, {@code handler} will not be called
     * @throws java.lang.IllegalStateException if the manager has not been initialized
     */
    public Boolean login(Activity activity, LoginHandler handler) {
        validateInitialization();

        loginHandler = handler;

        Intent intent = new Intent(activity, ProviderSelectActivity.class);
        activity.startActivityForResult(intent, INTENT_REQUEST_CODE_SELECT_ACTIVITY);
        return true;
    }

    /**
     * Method used to post message to user wall. Response will include general {@code success} flag
     * as well as detailed result as received from the server ({@link com.oneall.oneallsdk.MessagePostResult}
     *
     * @param text            body of the message to be posted
     * @param pictureUrl      (optional) url of the image to be posted
     * @param videoUrl        (optional) URL of video to be post
     * @param linkUrl         (optional) URL to attach to the post
     * @param linkName        (optional) name of the link posted; has no effect if {@code linkUrl} is {@code null}
     * @param linkCaption     (optional) caption of the link posted; has no effect if {@code linkUrl} is {@code null}
     * @param linkDescription (optional) description of the link posted; has no effect if {@code linkUrl} is {@code null}
     * @param enableTracking  should the {@code linkUrl} posted use OneAll link tracking?; has no effect if {@code linkUrl} is {@code null}
     * @param userToken       user token received as part of {@link com.oneall.oneallsdk.rest.models.User#userToken} object received during
     *                        authentication
     * @param publishToken    publish token received as part of {@link com.oneall.oneallsdk.rest.models.User#publishToken} object received during
     *                        authentication
     * @param providers       array of provider identifiers; list of providers can be obtained by {@link #getProviders()}
     * @param handler         response handler called on either posting success of failure
     * @throws java.lang.IllegalStateException if the manager has not been initialized
     * @see #getProviders()
     * @see com.oneall.oneallsdk.rest.models.User
     * @see com.oneall.oneallsdk.MessagePostResult
     */
    public void postMessage(
            String text,
            String pictureUrl,
            String videoUrl,
            String linkUrl,
            String linkName,
            String linkCaption,
            String linkDescription,
            Boolean enableTracking,
            String userToken,
            String publishToken,
            final Collection<String> providers,
            final OAManagerPostHandler handler) {

        validateInitialization();

        MessagePostService service = ServiceManagerProvider.getInstance().getPostService();
        PostMessageRequest request = new PostMessageRequest(
                providers,
                text,
                pictureUrl,
                videoUrl,
                linkUrl,
                linkName,
                linkCaption,
                linkDescription,
                enableTracking);

        OALog.info("Posting message to providers");

        service.post(
                userToken,
                ServiceManagerProvider.buildPublishAuthHeader(publishToken),
                request,
                new ServiceCallback<PostMessageResponse>() {
                    @Override
                    public void success(PostMessageResponse postMessageResponse, Response response) {
                        OALog.info(String.format("Message post succeeded: %s", response.toString()));
                        if (handler != null) {
                            handler.postComplete(true, postMessageResponse);
                        }
                    }

                    @Override
                    public void failure(ServiceError error) {
                        //noinspection ThrowableResultOfMethodCallIgnored
                        OALog.warn(String.format(
                                "Message post failed: %s", error.getRetrofitError().getMessage()));

                        if (handler != null) {
                            handler.postComplete(false, error.getResponse());
                        }
                    }
                });
    }

    /**
     * handler of onPostResume signal of parent activity
     */
    public void onPostResume(Activity activity) {
        /* it is impossible to work with GUI (specifically Fragments) from onActivityResult():
         * http://stackoverflow.com/questions/16265733/
         *
         * In order to overcome this, onActivityResult() stores the required operation in queue and
         * onPostResume() takes care of this when the state has been restored. An ugly solution for an
         * ugly problem.
         */
        if (loginOnResume) {
            String providerKey = loginOnResumeProvider;
            loginOnResume = false;
            loginOnResumeProvider = null;

            login(activity, providerKey, loginHandler);
        }
    }

    /**
     * Get list of supported providers. All providers are returned and not only ones configured for
     * specified application
     *
     * @return array of providers supported by OneAll API
     */
    public Collection<String> getProviders() {
        validateInitialization();

        ArrayList<String> rv = new ArrayList<>();
        for (Provider p : ProviderManager.getInstance().getProviders()) {
            rv.add(p.getKey());
        }
        return rv;
    }

    // endregion

    // region Utilities

    /**
     * callback taking care of connection details retrieval. This is the end of authentication
     * process, so the user of {@code OAManager} is informed using {@link #loginHandler} listener
     */
    private Callback<ResponseConnection> connectionCallback = new Callback<ResponseConnection>() {
        @Override
        public void success(ResponseConnection connection, Response response) {
            if (loginHandler != null) {
                loginHandler.loginSuccess(connection.data.user, false);
                loginHandler = null;
            }
        }

        @Override
        public void failure(RetrofitError error) {
            if (loginHandler != null) {
                loginHandler.loginFailure(new OAError(
                        OAError.ErrorCode.OA_ERROR_CONNECTION_ERROR,
                        mAppContext.getResources().getString(R.string.connection_failure)));
                loginHandler = null;
            }
        }
    };

    /**
     * handler of login completion by {@link com.oneall.oneallsdk.WebLoginActivity}
     *
     * @param data intent data filled in with login result
     */
    private void webLoginComplete(Intent data) {
        String url = data.getExtras().getString(WebLoginActivity.INTENT_EXTRA_URL);

        OALog.info(String.format("Web login completed with URL %s", url));

        ConnectionService service = ServiceManagerProvider.getInstance().getConnectionService();

        service.info(
                ServiceManagerProvider.buildAuthHeader(lastNonce),
                Uri.parse(url).getQueryParameter("connection_token"),
                connectionCallback);
    }

    /**
     * actual login with user information already filled in
     *
     * @param userInput user information if required by this provider, can be null
     */
    private void webLoginWithLoginData(Activity activity, Provider provider, String userInput) {
        String url = getApiUrlForProvider(provider, lastNonce, userInput);
        OALog.info(String.format(
                "Web login with provider %s and url: %s", provider.getKey(), url));
        Intent i = new Intent(activity, WebLoginActivity.class);
        i.putExtra(WebLoginActivity.INTENT_EXTRA_URL, url);

        activity.startActivityForResult(i, INTENT_REQUEST_CODE_LOGIN);
    }

    /**
     * starts actual web login with selected provider by opening web view with provider relevant
     * URL
     */
    private void webLoginWithProvider(Activity activity, final Provider provider) {
        OALog.info(String.format("Login with provider %s", provider));

        if (provider.getAuthentication().getIsUserInputRequired()) {
            FragmentManager fm = activity.getFragmentManager();
            final UserInputDialog dialog = new UserInputDialog();
            dialog.setListener(new UserInputDialog.DialogListener() {
                @Override
                public void onCancel() { }

                @Override
                public void onAccept(String userInput) {
                    webLoginWithLoginData(dialog.getActivity(), provider, userInput);
                }
            });

            Bundle args = new Bundle();
            args.putString(UserInputDialog.ARGUMENT_USER_INPUT_TYPE, provider.getAuthentication().getUserInputType());
            args.putString(UserInputDialog.ARGUMENT_PROVIDER_NAME, provider.getName());

            dialog.setArguments(args);
            dialog.show(fm, "user_input_dialog");
        } else {
            webLoginWithLoginData(activity, provider, null);
        }
    }

    /**
     * build URL used to start authentication process with specified provider
     *
     * @param provider  provider to use for login
     * @param nonce     nonce generated at the start of authentication process
     * @param loginData additional login data to be added to URL parameter
     * @return URL to open for user authentication
     */
    private String getApiUrlForProvider(Provider provider, String nonce, String loginData) {
        String url = String.format(
                "https://%s.api.oneall.com/socialize/connect/mobile/%s/?nonce=%s&callback_uri=oneall://%s",
                Settings.getInstance().getSubdomain(),
                provider.getKey(),
                nonce,
                provider.getKey());
        Uri.Builder uriBuilder = Uri.parse(url).buildUpon();
        if (loginData != null) {
            uriBuilder.appendQueryParameter("login_data", loginData);
        }

        return uriBuilder.build().toString();
    }

    /**
     * handler of native Facebook authentication failure
     *
     * @param error detailed error
     */
    private void facebookLoginFailure(OAError error) {
        OALog.warn(String.format("Failed to login with Facebook: %s", error.getMessage()));
        if (loginHandler != null) {
            loginHandler.loginFailure(error);
        }
    }

    /**
     * handler of successful native Facebook authentication
     *
     * @param guiContext  context used for GUI tasks
     * @param accessToken Facebook access token received during authentication
     */
    private void facebookLoginSuccess(Context guiContext, String accessToken) {
        OALog.info("Logged in with Facebook");
        retrieveConnectionInfo(guiContext, "facebook", accessToken, null);
    }

    /**
     * handler of native Twitter authentication failure
     *
     * @param error detailed error
     */
    private void twitterLoginFailure(OAError error) {
        OALog.warn(String.format("Failed to login with Twitter: %s", error.getMessage()));
        if (loginHandler != null) {
            loginHandler.loginFailure(
                    new OAError(OAError.ErrorCode.OA_ERROR_AUTH_FAIL,
                                error.getMessage()));
        }
    }

    /**
     * handler of successful authentication using native Twitter SDK
     *
     * @param guiContext  context used for GUI tasks
     * @param accessToken Twitter access token received after authentication process
     * @param secret      Twitter secret key received after authentication process
     */
    private void twitterLoginSuccess(Context guiContext, String accessToken, String secret) {
        OALog.info("Logged in with Twitter");
        retrieveConnectionInfo(guiContext, "twitter", accessToken, secret);
    }

    /**
     * after successful login, user information has to be retrieved, which is the responsibility of
     * this method
     *
     * @param guiContext  context used for GUI tasks
     * @param platform    platform with which the authentication is performed
     * @param accessToken (optional) access token received during native authentication (e.g.
     *                    Facebook or Twitter)
     * @param secret      (optional) secret key received during native authentication (e.g. Twitter)
     */
    private void retrieveConnectionInfo(
            Context guiContext, String platform, String accessToken, String secret) {

        try {
            final ProgressDialog pd = ProgressDialog.show(
                    guiContext,
                    guiContext.getString(R.string.reading_user_info_title),
                    guiContext.getString(R.string.reading_user_info_message),
                    true,
                    true);

            UserService service = ServiceManagerProvider.getInstance().getUserService();
            NativeLoginRequest request = new NativeLoginRequest(platform, accessToken, secret);

            service.info(request, new Callback<ResponseConnection>() {
                @Override
                public void success(ResponseConnection connection, Response response) {
                    // dismiss the dialog: since we created it with an app context
                    // we must explicitly request it to destroy itself
                    pd.dismiss();

                    if (loginHandler != null) {
                        loginHandler.loginSuccess(connection.data.user, false);
                        loginHandler = null;
                    }
                }

                @Override
                public void failure(RetrofitError error) {
                    pd.dismiss();

                    if (loginHandler != null) {
                        loginHandler.loginFailure(new OAError(
                                OAError.ErrorCode.OA_ERROR_CONNECTION_ERROR,
                                mAppContext.getResources().getString(R.string.connection_failure)));
                        loginHandler = null;
                    }
                }
            });
        } catch (WindowManager.BadTokenException e) {
            // the user backed out of the calling activity so we failed to show the loading view
            // notify the handler of a generic connection failure either way
            if (loginHandler != null) {
                loginHandler.loginFailure(new OAError(
                        ErrorCode.OA_ERROR_CONNECTION_ERROR,
                        mAppContext.getResources().getString(R.string.connection_failure)));
                loginHandler = null;
            }
        }
    }

    /** validate initialization state, throws an exception if the manager is not initialized */
    void validateInitialization() {
        if (mAppContext == null) {
            throw new IllegalStateException("Manager not initialized");
        }
    }

    // endregion

    // region Activity lifecycle responders

    /**
     * should be called by the using activity to process onCreate signal
     */
    public void onCreate(Activity activity, Bundle savedInstanceState) {
        FacebookWrapper.getInstance().onCreate(activity, savedInstanceState);
    }

    /**
     * should be called by the using activity to process onResume signal
     */
    public void onResume() {
        FacebookWrapper.getInstance().onResume();
    }

    /**
     * should be called by the using activity to process onPause signal
     */
    public void onPause() {
        FacebookWrapper.getInstance().onPause();
    }

    /**
     * should be called by the using activity to process onActivityResult signal
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (resultCode) {
            case Activity.RESULT_OK:
                if (requestCode == INTENT_REQUEST_CODE_SELECT_ACTIVITY) {
                    loginOnResumeProvider = data.getExtras().getString(ProviderSelectActivity.INTENT_EXTRA_PROVIDER);
                    loginOnResume = true;
                } else if (requestCode == INTENT_REQUEST_CODE_LOGIN) {
                    webLoginComplete(data);
                } else {
                    FacebookWrapper.getInstance().onActivityResult(requestCode, resultCode, data);
                    TwitterWrapper.getInstance().onActivityResult(requestCode, resultCode, data);
                }
                break;
            case Activity.RESULT_CANCELED:
                // let the native sdk's handle the result cancelled ev
                FacebookWrapper.getInstance().onActivityResult(requestCode, resultCode, data);
                TwitterWrapper.getInstance().onActivityResult(requestCode, resultCode, data);
                /* fall through */
            case WebLoginActivity.RESULT_FAILED:
                if (loginHandler != null) {
                    loginHandler.loginFailure(new OAError(OAError.ErrorCode.OA_ERROR_CANCELLED, null));
                    loginHandler = null;
                }
        }
    }

    /**
     * should be called by the using activity to process onSaveInstanceState signal
     */
    public void onSaveInstanceState(Bundle outState) {
        FacebookWrapper.getInstance().onSaveInstanceState(outState);
    }

    /**
     * should be called by the using activity to process onDestroy signal
     */
    public void onDestroy() {
        FacebookWrapper.getInstance().onDestroy();
    }

    // endregion

}
