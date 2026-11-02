package it.spesami.core

import java.text.Normalizer
import java.util.Locale

/**
 * Offline dictionary of common Italian groceries -> default category key ([DefaultCategories]).
 *
 * Matching, in order: the whole name; a packaging qualifier ("in scatola", "surgelati"…); otherwise the longest
 * dictionary phrase found in the name. If phrases of that same length point to different categories
 * ("latte di cocco": latte / cocco) the match is ambiguous and nothing is suggested.
 */
object IngredientDictionary {
    private val byCategory = mapOf(
        "frutta_verdura" to """
            patate, patata, patate novelle, carote, carota, zucchine, zucchina, zucchini, spinaci, spinacio,
            pomodori, pomodoro, pomodorini, pomodorino, pomodori ciliegini, insalata, lattuga, iceberg, rucola,
            radicchio, valeriana, songino, cipolle, cipolla, cipolla rossa, cipollotti, cipollotto, aglio, scalogno,
            porri, porro, sedano, finocchi, finocchio, melanzane, melanzana, peperoni, peperone, cetrioli, cetriolo,
            broccoli, broccolo, cavolfiore, cavolo, cavolo nero, cavolo cappuccio, verza, cavolini di bruxelles,
            carciofi, carciofo, asparagi, fagiolini, funghi, funghi champignon, champignon, porcini, zucca, bietole,
            bieta, coste, cime di rapa, friarielli, barbabietole, rape, ravanelli, prezzemolo, basilico, rosmarino,
            salvia, menta, erba cipollina, mele, mela, pere, pera, banane, banana, arance, arancia, mandarini,
            mandarino, clementine, limoni, limone, lime, uva, fragole, kiwi, ananas, pesche, pesca, albicocche,
            prugne, susine, ciliegie, melone, anguria, frutti di bosco, mirtilli, lamponi, more, avocado, zenzero,
            frutta, verdura, verdure, castagne, fichi, melograno, cachi, cocco, germogli, mais dolce fresco
        """,
        "carne_salumi" to """
            carne, carne macinata, macinato, macinato di manzo, macinato misto, manzo, vitello, maiale, pollo,
            petto di pollo, petti di pollo, cosce di pollo, fusi di pollo, ali di pollo, sovracosce, tacchino,
            petto di tacchino, fesa di tacchino, agnello, coniglio, salsiccia, salsicce, wurstel, hamburger,
            polpette, bistecca, bistecche, fettine, fettine di vitello, scaloppine, spezzatino, arrosto, cotolette,
            braciole, costine, lonza, filetto di manzo, roast beef, prosciutto, prosciutto cotto, prosciutto crudo,
            speck, salame, salamino, mortadella, bresaola, pancetta, pancetta affumicata, guanciale, coppa, lardo,
            cotechino, zampone, salumi, affettati, carpaccio
        """,
        "pesce_surgelati" to """
            pesce, salmone, salmone affumicato, merluzzo, baccala, stoccafisso, branzino, spigola, orata, sogliola,
            platessa, trota, pesce spada, tonno fresco, sgombro, alici, gamberi, gamberetti, scampi, mazzancolle,
            calamari, seppie, polpo, totani, cozze, vongole, frutti di mare, bastoncini di pesce, filetti di merluzzo,
            surgelati, gelato, gelati, sofficini, minestrone, cuori di merluzzo, pizza surgelata
        """,
        "formaggi" to """
            formaggio, formaggi, parmigiano, parmigiano reggiano, grana, grana padano, pecorino, pecorino romano,
            mozzarella, mozzarelle, mozzarella di bufala, fiordilatte, burrata, stracciatella, ricotta, mascarpone,
            gorgonzola, fontina, asiago, provola, provola affumicata, scamorza, provolone, taleggio, stracchino,
            crescenza, emmental, groviera, gruyere, fiocchi di latte, feta, philadelphia, formaggio spalmabile,
            sottilette, caciocavallo, brie, edamer, montasio, castelmagno, caciotta, robiola, quartirolo
        """,
        "latte_latticini" to """
            latte, latte intero, latte parzialmente scremato, latte scremato, latte senza lattosio, latte fresco,
            yogurt, yogurt greco, yogurt bianco, burro, panna, panna da cucina, panna fresca, panna montata,
            panna acida, uova, uovo, kefir, latticini
        """,
        "scatolame_conserve" to """
            tonno, tonno in scatola, sgombro in scatola, ceci, fagioli, fagioli borlotti, fagioli cannellini,
            cannellini, borlotti, lenticchie, piselli, mais, pelati, pomodori pelati, passata, passata di pomodoro,
            polpa di pomodoro, concentrato di pomodoro, sugo pronto, sugo, olive, olive nere, olive verdi, capperi,
            acciughe, sottaceti, carciofini, legumi, fagioli neri, cicerchie, conserve, scatolame, pomodori secchi
        """,
        "pasta_riso_cereali" to """
            pasta, spaghetti, spaghetti integrali, penne, penne rigate, fusilli, rigatoni, maccheroni, farfalle,
            linguine, tagliatelle, fettuccine, pappardelle, lasagne, pasta all uovo, pasta integrale, gnocchi,
            gnocchi di patate, tortellini, ravioli, agnolotti, cappelletti, orecchiette, trofie, paccheri,
            mezze maniche, ditalini, pastina, conchiglie, bucatini, sedanini, tubetti, vermicelli, riso, riso arborio,
            riso carnaroli, riso basmati, riso integrale, riso venere, orzo, farro, quinoa, couscous, cous cous,
            bulgur, polenta, farina, farina 00, farina integrale, farina di mais, semola, fecola, amido di mais,
            maizena, zucchero, zucchero di canna, zucchero a velo, noodles
        """,
        "pane_forno" to """
            pane, pane integrale, pane in cassetta, pancarre, panini, panino, panini per hamburger, baguette,
            ciabatta, rosetta, focaccia, piadina, piadine, grissini, crackers, cracker, taralli, friselle, pangrattato,
            pan grattato, pasta sfoglia, pasta frolla, pasta brisee, pasta per pizza, base per pizza,
            impasto per pizza, lievito, lievito di birra, lievito per dolci, tortillas, pane carasau, pan di spagna,
            savoiardi, crostini
        """,
        "colazione_merende" to """
            fette biscottate, biscotti, cereali, corn flakes, muesli, fiocchi d avena, fiocchi di avena, avena,
            marmellata, confettura, miele, nutella, crema spalmabile, crema di nocciole, cacao, cioccolato,
            cioccolata, cioccolato fondente, gocce di cioccolato, caffe, caffe macinato, capsule caffe, te,
            tisana, tisane, camomilla, orzo solubile, merendine, crostatine, brioche, cornetti, croissant, plumcake,
            frutta secca, noci, mandorle, nocciole, pistacchi, arachidi, uvetta, budino, budini, gallette,
            gallette di riso, barrette, snack, patatine, popcorn
        """,
        "condimenti_spezie_salse" to """
            olio, olio extravergine, olio extravergine di oliva, olio d oliva, olio di oliva, olio di semi, olio evo,
            olio di girasole, aceto, aceto balsamico, aceto di vino, aceto di mele, sale, sale grosso, sale fino,
            pepe, pepe nero, peperoncino, origano, paprika, curry, curcuma, cannella, noce moscata, zafferano,
            cumino, alloro, timo, maionese, ketchup, senape, salsa di soia, pesto, pesto alla genovese, dado,
            dado da brodo, brodo, brodo vegetale, brodo di carne, besciamella, spezie, erbe aromatiche, salsa, salse,
            tabasco, salsa barbecue, vanillina, pinoli, semi di sesamo, sesamo, aglio in polvere
        """,
        "bevande" to """
            acqua, acqua naturale, acqua frizzante, acqua minerale, vino, vino bianco, vino rosso, birra, succo,
            succo di frutta, succo d arancia, succo di mela, aranciata, coca cola, cola, bibite, bibita, spremuta,
            te freddo, tonica, chinotto, aperitivo, prosecco, spumante, latte di mandorla, bevanda di soia,
            bevanda di avena
        """,
        DefaultCategories.OTHER to """
            carta igienica, detersivo, detersivo piatti, sapone, carta da forno, pellicola, carta stagnola,
            alluminio, tovaglioli, sacchetti, sacchetti gelo, spugne, carta assorbente, scottex, candeggina,
            ammorbidente, dentifricio, shampoo, fazzoletti
        """,
    )

    private val entries: Map<String, String> = buildMap {
        byCategory.forEach { (key, list) ->
            list.split(',').map { normalize(it) }.filter { it.isNotEmpty() }.forEach { phrase ->
                check(put(phrase, key) == null) { "Duplicate dictionary entry: $phrase" }
            }
        }
    }

    private val maxWords = entries.keys.maxOf { it.count { c -> c == ' ' } + 1 }

    private val preservedQualifiers = listOf("in scatola", "sott olio", "sottolio", "sottaceto", "sott aceto", "in barattolo", "sottovuoto")

    /** Lower case, no accents, apostrophes and punctuation as spaces: "Caffè d'orzo" -> "caffe d orzo". */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** The suggested default category key for [name], or null when unknown or ambiguous. */
    fun suggest(name: String): String? {
        val normalized = normalize(name)
        if (normalized.isEmpty()) return null
        entries[normalized]?.let { return it }

        val padded = " $normalized "
        if (preservedQualifiers.any { padded.contains(" $it ") }) return "scatolame_conserve"
        val words = normalized.split(' ')
        if (words.any { it.startsWith("surgelat") || it.startsWith("congelat") }) return "pesce_surgelati"

        for (length in minOf(words.size, maxWords) downTo 1) {
            val found = (0..words.size - length)
                .mapNotNull { start -> entries[words.subList(start, start + length).joinToString(" ")] }
                .toSet()
            if (found.isNotEmpty()) return found.singleOrNull()
        }
        return null
    }

    /** Dictionary size, for tests and the info text. */
    val size: Int get() = entries.size
}
