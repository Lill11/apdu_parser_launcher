public sealed interface ParsedLogEvent permits ParsedLogEvent.Apdu, ParsedLogEvent.Reset {

    int sourceLine();

    record Apdu(String command, int sourceLine) implements ParsedLogEvent {
        public Apdu {
            command = command == null ? "" : command;
        }
    }

    record Reset(ResetType resetType, String atr, int sourceLine) implements ParsedLogEvent {
        public Reset {
            resetType = resetType == null ? ResetType.COLD_RESET : resetType;
            atr = atr == null ? "" : atr;
        }
    }

    enum ResetType {
        COLD_RESET
    }
}
