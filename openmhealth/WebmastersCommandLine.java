import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;

import com.google.api.services.webmasters.Webmasters;
import com.google.api.services.webmasters.model.SitesListResponse;
import com.google.api.services.webmasters.model.WmxSite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;


public class WebmastersCommandLine {

  private static String CLIENT_ID = "732059021015-m2m5t96lmjk1duuvolmejd8jocbocmb9.apps.googleusercontent.com";
  private static String CLIENT_SECRET = "znxbHiKf_R7_KUg_602VU7Nv";

  private static String REDIRECT_URI = "https://raspberrypi3url.example.com/callback";

  private static String OAUTH_SCOPE = "https://www.googleapis.com/auth/webmasters.readonly";

  public static void main(String[] args) throws IOException {
    HttpTransport httpTransport = new NetHttpTransport();
    JsonFactory jsonFactory = new JacksonFactory();

    GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
        httpTransport, jsonFactory, CLIENT_ID, CLIENT_SECRET, Arrays.asList(OAUTH_SCOPE))
        .setAccessType("online")
        .setApprovalPrompt("auto").build();

    String url = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
    System.out.println("Please open the following URL in your browser then type the authorization code:");
    System.out.println("  " + url);
    System.out.println("Enter authorization code:");
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    String code = br.readLine();

    GoogleTokenResponse response = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute();
    GoogleCredential credential = new GoogleCredential().setFromTokenResponse(response);

    // Create a new authorized API client
    Webmasters service = new Webmasters.Builder(httpTransport, jsonFactory, credential)
        .setApplicationName("WebmastersCommandLine")
        .build();

    List<String> verifiedSites = new ArrayList<String>();
    Webmasters.Sites.List request = service.sites().list();

    // Get all sites that are verified
    try {
      SitesListResponse siteList = request.execute();
      for (WmxSite currentSite : siteList.getSiteEntry()) {
        String permissionLevel = currentSite.getPermissionLevel();
        if (permissionLevel.equals("siteOwner")) {
          verifiedSites.add(currentSite.getSiteUrl());
        }
      }
    } catch (IOException e) {
      System.out.println("An error occurred: " + e);
    }

    // Print all verified sites
    for (String currentSite : verifiedSites) {
      System.out.println(currentSite);
    }
  }
}
