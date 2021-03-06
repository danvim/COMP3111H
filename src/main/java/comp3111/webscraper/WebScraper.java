package comp3111.webscraper;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;


/**
 * WebScraper provide a sample code that scrape web content. After it is constructed, you can call the method scrape with a keyword,
 * the client will go to the default url and parse the page by looking at the HTML DOM.
 *
 * In this particular sample code, it access to craigslist.org. You can directly search on an entry by typing the URL
 *
 * https://newyork.craigslist.org/search/sss?sort=rel&amp;query=KEYWORD
 *
 * where KEYWORD is the keyword you want to search.
 *
 * Assume you are working on Chrome, paste the url into your browser and press F12 to load the source code of the HTML. You might be freak
 * out if you have never seen a HTML source code before. Keep calm and move on. Press Ctrl-Shift-C (or CMD-Shift-C if you got a mac) and move your
 * mouse cursor around, different part of the HTML code and the corresponding the HTML objects will be highlighted. Explore your HTML page from
 * body &rarr; section class="page-container" &rarr; form id="searchform" &rarr; div class="content" &rarr; ul class="rows" &rarr; any one of the multiple
 * li class="result-row" &rarr; p class="result-info". You might see something like this:
 *
 * <pre>{@code
 *    <p class="result-info">
 *        <span class="icon icon-star" role="button" title="save this post in your favorites list">
 *           <span class="screen-reader-text">favorite this post</span>
 *       </span>
 *       <time class="result-date" datetime="2018-06-21 01:58" title="Thu 21 Jun 01:58:44 AM">Jun 21</time>
 *       <a href="https://newyork.craigslist.org/que/clt/d/green-star-polyp-gsp-on-rock/6596253604.html" data-id="6596253604" class="result-title hdrlnk">Green Star Polyp GSP on a rock frag</a>
 *       <span class="result-meta">
 *               <span class="result-price">$15</span>
 *               <span class="result-tags">
 *                   pic
 *                   <span class="maptag" data-pid="6596253604">map</span>
 *               </span>
 *               <span class="banish icon icon-trash" role="button">
 *                   <span class="screen-reader-text">hide this posting</span>
 *               </span>
 *           <span class="unbanish icon icon-trash red" role="button" aria-hidden="true"></span>
 *           <a href="#" class="restore-link">
 *               <span class="restore-narrow-text">restore</span>
 *               <span class="restore-wide-text">restore this posting</span>
 *           </a>
 *       </span>
 *   </p>
 * }</pre>
 *
 * The code
 * <pre>{@code
 * List<?> items = (List<?>) page.getByXPath("//li[@class='result-row']");
 * }</pre>
 * extracts all result-row and stores the corresponding HTML elements to a list called items. Later in the loop it extracts the anchor tag
 * &lsaquo; a &rsaquo; to retrieve the display text (by .asText()) and the link (by .getHrefAttribute()). It also extracts
 *
 * @author CHEUNG, Daniel
 */
public class WebScraper {

    private static final float USD2HKD = 7.8f;
    private static final DateFormat ISO_8061_FORMATTER = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ"); //2017-06-08T15:22:17.000Z
    private static final DateFormat CRAIGSLIST_DATETIME_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm"); //2017-06-08T15:22:17.000Z
    private static final String PORTAL_CRAIGSLIST = "Craigslist";
    private static final String PORTAL_CAROUSELL = "Carousell";


    private WebClient client;
    private ScriptEngine scriptEngine = new ScriptEngineManager().getEngineByName("nashorn");

    /**
     * Default Constructor
     */
    WebScraper() {
        client = new WebClient();
        client.getOptions().setCssEnabled(false);
        client.getOptions().setJavaScriptEnabled(false);
    }

    /**
     * Scrapes from all portals
     *
     * @param keyword - the keyword you want to search
     * @return A list of Item that has found. A zero size list is return if nothing is found. Null if any exception (e.g. no connectivity)
     */
    List<Item> scrape(String keyword) {

        List<Item> allItems = new ArrayList<>();
        allItems.addAll(scrapeCraigslist(keyword));
        allItems.addAll(scrapeCarousell(keyword));

        allItems.sort((a, b) -> Boolean.compare(a.getPortal().equals(PORTAL_CAROUSELL), b.getPortal().equals(PORTAL_CAROUSELL)));
        allItems.sort(Comparator.comparingDouble(Item::getPrice));

        return allItems;
    }

    /**
     * Scrapes from Carousell
     *
     * @param keyword The search keyword
     * @return A List of search results as Items
     */
    private List<Item> scrapeCarousell(String keyword) {
        List<Item> results = new ArrayList<>();

        try {
            String searchURL = "https://hk.carousell.com/search/products/?query=" + URLEncoder.encode(keyword, "UTF-8");
            HtmlPage page = client.getPage(searchURL);

            DomNodeList<DomElement> scriptTags = page.getElementsByTagName("script");
            String appScript = scriptTags.stream().map(DomNode::getTextContent)
                    .filter(script -> script.contains("window.App")).findFirst().orElse("");

            if (!appScript.isEmpty()) {
                scriptEngine.eval("var window = {}; " + appScript);
                ScriptObjectMirror mirror = (ScriptObjectMirror) scriptEngine.eval("window.App.context.dispatcher.stores.ProductStore.productsMap");

                for (Map.Entry<String, Object> productObject : mirror.entrySet()) {
                    ScriptObjectMirror product = (ScriptObjectMirror) productObject.getValue();

                    /*
                    id: String
                    title: String
                    price: String, "HK$2,500"
                    timeCreated: String "2016-08-21T17:28:14.000Z"

                    url in form of
                        https://hk.carousell.com/p/title-id
                    this is tested ok
                        https://hk.carousell.com/p/id
                     */

                    results.add(new Item(
                            (String) product.get("title"),
                            Double.parseDouble(((String) product.get("price")).replaceAll("[HK$,]", "")),
                            "https://hk.carousell.com/p/" + product.get("id"),
                            ISO_8061_FORMATTER.parse(((String) product.get("timeCreated")).replace("Z", "+0000")),
                            PORTAL_CAROUSELL
                    ));
                }
            }
        } catch (IOException | ParseException | ScriptException e) {
            e.printStackTrace();
        }

        return results;
    }

    /**
     * Scrapes from Craigslist
     *
     * @param keyword The search keyword
     * @return A List of search results as Items
     */
    private List<Item> scrapeCraigslist(String keyword) {
        List<Item> results = new ArrayList<>();

        try {
            String searchUrl = "https://newyork.craigslist.org/search/sss?sort=rel&query=" + URLEncoder.encode(keyword, "UTF-8");
            HtmlPage page = client.getPage(searchUrl);

            List<?> items = page.getByXPath("//li[@class='result-row']");

            for (Object item1 : items) {
                HtmlElement htmlItem = (HtmlElement) item1;
                HtmlAnchor itemAnchor = htmlItem.getFirstByXPath(".//p[@class='result-info']/a");
                HtmlElement spanPrice = htmlItem.getFirstByXPath(".//a/span[@class='result-price']");
                HtmlElement dateElement = htmlItem.getFirstByXPath(".//p[@class='result-info']/time");

                // It is possible that an item doesn't have any price, we set the price to 0.0
                // in this case
                String itemPrice = spanPrice == null ? "0.0" : spanPrice.asText();

                results.add(new Item(
                        itemAnchor.asText(),
                        Math.round(Double.parseDouble(itemPrice.replace("$", "")) * USD2HKD * 100.0) / 100.0,
                        itemAnchor.getHrefAttribute(),
                        CRAIGSLIST_DATETIME_FORMATTER.parse(dateElement.getAttribute("datetime")),
                        PORTAL_CRAIGSLIST
                ));
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }

        return results;
    }

}
