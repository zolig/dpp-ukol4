package cz.dpp.ukol4.examples;

import java.util.ArrayList;
import java.util.List;

import cz.dpp.ukol2.argparse.PlainArgs;

public class ListSubcmdArgs {
    public boolean timestamps;
    
    @PlainArgs
    public List<String> args = new ArrayList<>();    
}
