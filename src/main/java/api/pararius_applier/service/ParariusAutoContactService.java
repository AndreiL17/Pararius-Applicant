package api.pararius_applier.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.http.ConnectionFailedException;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This @Service runs every CHECK_INTERVAL_MINUTES (default 30) to:
 * 1. Scrape the Pararius Groningen search page.
 * 2. Find any new listings not yet in seen_listings.txt.
 * 3. For each new listing, open it, click Contact → fill (if needed) → Send.
 * 4. Record that URL in seen_listings.txt so it’s never processed twice.
 */
@Service
public class ParariusAutoContactService {

    private static final String SEARCH_URL = "https://www.pararius.com/apartments/groningen/apartment";
    private static final String LISTING_LINK_SELECTOR = "a.listing-search-item__link";
    private static final String CONTACT_BUTTON_SELECTOR = "button.listing-reaction-button--contact-agent";
    private static final String SEND_BUTTON_SELECTOR = "button.listing-contact__submit";

    private static final String YOUR_NAME = "Andrei Lentu";
    private static final String YOUR_EMAIL = "andrei.lentu@gmail.com";
    private static final String YOUR_MESSAGE =
            "Hello,\n" +
                    "\n" +
                    "We are Andrei and Sabina, a couple studying Computer Science in our second year at university. We currently live at Donderslaan 62, but our contract is ending soon and we would love to continue staying in a good location like this one. Aside from our studies, we are both software engineering interns for ING. We are very quite people, we play no instruments, have no pets and do not host parties.\n" +
                    "\n" +
                    "We would be interested in attending a viewing if possible";

    private static final String SEEN_LISTINGS_FILE = "seen_listings.txt";

    private static final long CHECK_INTERVAL_MS = 30 * 60 * 1000L;

    private final Set<String> seenListings = new HashSet<>();

    // Cutoff date = “Offered since” of https://www.pararius.com/apartment-for-rent/groningen/2f968975/oosterhaven  (04-06-2025) :contentReference[oaicite:0]{index=0}
    private static final LocalDate CUTOFF_DATE = LocalDate.of(2025, 6, 4);

    /**
     * On startup, load any previously‐seen listings from disk.
     */
    @PostConstruct
    public void loadSeenListings() {
        File file = new File(SEEN_LISTINGS_FILE);
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                System.err.println("Could not create seen listings file: " + e.getMessage());
            }
            return;
        }

        try {
            Files.lines(Paths.get(SEEN_LISTINGS_FILE))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .forEach(seenListings::add);
        } catch (IOException e) {
            System.err.println("Error reading seen_listings.txt: " + e.getMessage());
        }
    }

    /**
     * Append a new URL to seen_listings.txt (and to in-memory Set) so we never process it twice.
     */
    private synchronized void markListingAsSeen(String url) {
        if (seenListings.contains(url)) {
            return;
        }
        seenListings.add(url);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(SEEN_LISTINGS_FILE, true))) {
            writer.write(url);
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Error writing to seen_listings.txt: " + e.getMessage());
        }
    }

    /**
     * This method runs immediately on startup (initialDelay = 0) and then every CHECK_INTERVAL_MS.
     * It spins up a headless Chrome, scrapes all listing links, and processes any new ones.
     */
    @Scheduled(initialDelay = 0, fixedRate = CHECK_INTERVAL_MS)
    public void checkForNewListings() {
        System.out.println("=== Running Pararius check at " + LocalDateTime.now() + " ===");

        // 1. Set up headless ChromeDriver (attached to your existing Chrome via debuggerAddress)
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.setExperimentalOption("debuggerAddress", "localhost:9222");
        options.addArguments("--window-size=1920,1080");
        options.setPageLoadTimeout(Duration.ofSeconds(30));

        WebDriver driver = new ChromeDriver(options);
        try {
            // 2. Navigate to the Groningen search page
            driver.get(SEARCH_URL);
            Thread.sleep(3000); // wait a bit for all listings to render

            // 3. Grab all link‐elements from the search page
            List<WebElement> listingElements =
                    driver.findElements(By.cssSelector(LISTING_LINK_SELECTOR));
            System.out.println("→ Found " + listingElements.size() + " total listings on the search page.");

            // 4. Extract every href into a plain List<String> so we never use stale WebElements later:
            List<String> listingHrefs = new java.util.ArrayList<>();
            for (WebElement linkElem : listingElements) {
                String href = linkElem.getAttribute("href");
                if (href != null && !href.trim().isEmpty()) {
                    listingHrefs.add(href.trim());
                }
            }

            // 5. Now iterate over each URL string—opening a new tab/driver for each won't stale this list
            for (String href : listingHrefs) {
                if (seenListings.contains(href)) {
                    continue;
                }

                System.out.println("→ New listing detected: " + href);
                boolean success = processSingleListing(href);
                if (success) {
                    markListingAsSeen(href);
                    System.out.println("✓ Successful on : " + href);
                } else {
                    System.err.println("✗ Failed to process: " + href);
                }
                Thread.sleep(2000); // pause a bit between listings
            }

        } catch (InterruptedException e) {
            System.err.println("Interrupted during check: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            driver.quit();
        }
    }


    /**
     * Open a fresh headless ChromeDriver for exactly this listing URL, click Contact → (fill) → Send.
     * @param listingUrl the detail‐page URL to process
     * @return true if we clicked Send (or succeeded/skipped), false if something threw an exception
     */
    private boolean processSingleListing(String listingUrl) {
        WebDriver localDriver = null;
        try {
            // 1) Try to launch / attach to ChromeDriver
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.setExperimentalOption("debuggerAddress", "localhost:9222");
            options.setPageLoadTimeout(Duration.ofSeconds(30));

            try {
                localDriver = new ChromeDriver(options);
            } catch (ConnectionFailedException e) {
                return true;
            }

            // 2) If we got a driver, proceed as before:
            localDriver.get(listingUrl);

            // Extract the “Offered since” date and compare with cutoff
            try {
                WebDriverWait dateWait = new WebDriverWait(localDriver, Duration.ofSeconds(5));
                WebElement offeredSinceElem = dateWait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                By.xpath("//dt[contains(normalize-space(.), 'Offered since')]/following-sibling::dd[1]")
                        )
                );
                String offeredText = offeredSinceElem.getText().trim(); // e.g. "04-06-2025"
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy");
                LocalDate offeredDate = LocalDate.parse(offeredText, formatter);
                if (offeredDate.isBefore(CUTOFF_DATE)) {
                    return true; // skip older listings
                }
            } catch (Exception dateEx) {
                // If we cannot find or parse the date, skip processing but mark as seen to avoid infinite retries
                return true;
            }

            WebDriverWait shortWait = new WebDriverWait(localDriver, Duration.ofSeconds(5));

            try {
                WebElement acceptCookies = shortWait.until(ExpectedConditions.elementToBeClickable(
                        By.id("onetrust-accept-btn-handler")
                ));
                acceptCookies.click();
                Thread.sleep(500);
            } catch (Exception ignore) {
            }

            WebElement contactElement;
            try {
                contactElement = shortWait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("button.listing-reaction-button--contact-agent, " +
                                "a.listing-reaction-button--contact-agent")
                ));
            } catch (Exception cssEx) {
                try {
                    contactElement = shortWait.until(ExpectedConditions.elementToBeClickable(
                            By.xpath("//a[contains(normalize-space(.), 'Contact the estate agent')]")
                    ));
                } catch (Exception xpathEx) {
                    return true;
                }
            }

            contactElement.click();

            try {
                WebElement sendButton = shortWait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[contains(normalize-space(.), 'Send')]")
                ));

                try {
                    WebElement nameInput = localDriver.findElement(By.cssSelector("input#contact-name"));
                    if (nameInput.isDisplayed() && nameInput.getAttribute("value").isEmpty()) {
                        nameInput.clear();
                        nameInput.sendKeys(YOUR_NAME);
                    }
                } catch (Exception ignore) { }

                try {
                    WebElement emailInput = localDriver.findElement(By.cssSelector("input#contact-email"));
                    if (emailInput.isDisplayed() && emailInput.getAttribute("value").isEmpty()) {
                        emailInput.clear();
                        emailInput.sendKeys(YOUR_EMAIL);
                    }
                } catch (Exception ignore) { }

                try {
                    WebElement messageInput = localDriver.findElement(By.cssSelector("textarea#contact-message"));
                    if (messageInput.isDisplayed() && messageInput.getText().trim().isEmpty()) {
                        messageInput.clear();
                        messageInput.sendKeys(YOUR_MESSAGE);
                    }
                } catch (Exception ignore) { }

                sendButton.click();
                return true;

            } catch (Exception noSend) {
                return true;
            }

        } catch (Exception e) {
            System.err.println("Exception while processing " + listingUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (localDriver != null) {
                localDriver.quit();
            }
        }
    }

}
