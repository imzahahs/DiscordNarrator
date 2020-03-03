package sengine.sheets;

public class ParseException extends RuntimeException {
    private static String collapseMessage(String s, Throwable t) {
        if(t != null) {
            // Check if can find a ParseException down the line
            Throwable p = t;
            do {
                if(p instanceof ParseException) {
                    t = p;      // Found something down the line, use this
                    break;
                }
                else
                    p = p.getCause();
            } while(p != null);

            if(!(t instanceof ParseException)) {
                // Else collapse all messages
                p = t;
                do {
                    String message = p.getMessage();
                    if (message != null && !(message = message.trim()).isEmpty()) {
                        if(s == null || s.isEmpty())
                            s = "Because: " + message;
                        else
                            s += "\nBecause: " + message;
                    }
                    p = p.getCause();
                } while(p != null);
            }
            else {
                String message = t.getMessage();
                if (message != null && !(message = message.trim()).isEmpty()) {
                    if(s == null || s.isEmpty())
                        s = message;
                    else
                        s += "\n" + message;
                }
            }
        }
        return "\n" + s;
    }

    private static Throwable collapseCause(Throwable t) {
        // Check if can find a ParseException down the line
        Throwable p = t;
        while(p != null) {
            if(p instanceof ParseException) {
                if(p.getCause() != null)
                    t = p.getCause();      // Found something down the line, use this
                else {
                    t = null;
                    break;
                }
                p = t;
            }
            else
                p = p.getCause();
        }
        return t;
    }

    public ParseException(String s) {
        this(s, null);
    }

    public ParseException(String s, Throwable throwable) {
        super(collapseMessage(s, throwable), collapseCause(throwable));
    }
}
