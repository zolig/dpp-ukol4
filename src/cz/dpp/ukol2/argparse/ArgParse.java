package cz.dpp.ukol2.argparse;

import cz.dpp.ukol2.argparse.exceptions.MandatoryNotPresentException;
import cz.dpp.ukol2.argparse.exceptions.MissingValueException;
import cz.dpp.ukol2.argparse.exceptions.UnrecognizedArgumentException;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Argument parser and annotation processor class
 */
public class ArgParse {
	private final Map<String, ArgParse> subcommands = new HashMap<>();

    /** Set of all recognized arguments */
    private final Set<ArgumentDefinition> arguments = new HashSet<>();
    /** Map of short option names */
    private final Map<String, ArgumentDefinition> shortNames = new HashMap<>();
    /** Map of long option names */
    private final Map<String, ArgumentDefinition> longNames = new HashMap<>();

    /** Plain arguments field, if present */
    private Field plainArgsField = null;

    /** Argument class with annotated fields */
    private final Class<?> formatDefinition;

    /**
     * Create ArgParse object from an argument class
     * @param definition properly annotated argument class
     */
    private ArgParse (Class<?> definition) {
        this.formatDefinition = definition;

        for (Field field : definition.getDeclaredFields()) {
            // skip ignored and synthetic fields
            if (field.isAnnotationPresent(Ignore.class)) continue;
            if (field.isSynthetic()) continue;
            
        	if (field.isAnnotationPresent(Subcommand.class)) {
                ArgParse ap = new ArgParse(field.getType());
                subcommands.put(field.getName(), ap);
                continue;
        	}

            // check for presence of @PlainArgs field
            if (field.isAnnotationPresent(PlainArgs.class)) {
                if (plainArgsField != null) {
                    throw new RuntimeException(String.format("ArgParse: @PlainArgs specified twice ('%s' and '%s')", plainArgsField.getName(), field.getName()));
                }
                plainArgsField = field;
                // check that it is a Collection<String>
                if (!Collection.class.isAssignableFrom(plainArgsField.getType())) {
                    throw new RuntimeException(String.format("ArgParse: @PlainArgs field '%s' is not a collection", plainArgsField.getName()));
                }
                Type plainArgsType = plainArgsField.getGenericType();
                if (!(plainArgsType instanceof ParameterizedType)) {
                    throw new RuntimeException(String.format("ArgParse: @PlainArgs field '%s' is not a Collection<String>", plainArgsField.getName()));
                }
                Type[] typeArgs = ((ParameterizedType)plainArgsType).getActualTypeArguments();
                assert typeArgs.length == 1;
                if (!String.class.isAssignableFrom((Class<?>)typeArgs[0])) {
                    throw new RuntimeException(String.format("ArgParse: @PlainArgs field '%s' is not a Collection<String>", plainArgsField.getName()));
                }

                continue;
            }

            // generate definition from field
            ArgumentDefinition argument = new ArgumentDefinition(field);
            arguments.add(argument);

            // handle assignment of short names
            if (argument.hasDefaultShortName()) {
                assert argument.getShortNames().size() == 1; // exactly one short name is generated by default
                Iterator<String> fetchOneShortName = argument.getShortNames().iterator();
                String oneShortName = fetchOneShortName.next();
                if (shortNames.containsKey(oneShortName)) {
                    // this short name is already taken, new field doesn't get any
                    argument.getShortNames().clear();
                } else {
                    shortNames.put(oneShortName, argument);
                }
            } else {
                // check for duplicates, raise exceptions
                for (String shortName : argument.getShortNames()) {
                    if (shortNames.containsKey(shortName)) {
                        ArgumentDefinition contender = shortNames.get(shortName);
                        throw new RuntimeException(String.format("ArgParse: arguments '%s' and '%s' collide on short name '%s'.", argument, contender, shortName));
                    } else {
                        shortNames.put(shortName, argument);
                    }
                }
            }

            // check collisions for long names
            for (String longName : argument.getLongNames()) {
                if (longNames.containsKey(longName)) {
                    ArgumentDefinition contender = longNames.get(longName);
                    throw new RuntimeException(String.format("ArgParse: arguments '%s' and '%s' collide on short name '%s'.", argument, contender, longName));
                } else {
                    longNames.put(longName, argument);
                }
            }
        }
    }

    /** Generate help text from argument definitions
     *
     * <p>Example:
     *
     * <pre>
     *     -s 1..100
     *     --size=1..100    size of your shoes
     *     -v
     *     --verbose    be verbose
     *     -V
     *     --version    print version and exit
     * </pre>
     * @return string containing the help text, suitable for printing along with usage information
     */
    public String getHelp() {
        return getHelp("");
    }
    
    public String getHelp(String prefix) {
        StringBuilder sb = new StringBuilder();

		for (Map.Entry<String, ArgParse> entry : subcommands.entrySet()) {		    
		    sb.append(String.format("\t%s\n %s", entry.getKey(), entry.getValue().getHelp(prefix + "\t")));
		}        
        
        for (ArgumentDefinition argument : arguments) {
            sb.append(argument.getHelp(prefix));
        }
        return sb.toString();
    }

    /**
     * Parse command-line arguments, starting at <tt>offset</tt>, and fill out the supplied argument object
     * @param args string arguments from command line
     * @param argObject argument object to fill
     * @param offset first argument to parse
     * @throws ArgParseException
     */
    public void parseArgs (String[] args, Object argObject, int offset)
    throws ArgParseException {
        if (!formatDefinition.isAssignableFrom(argObject.getClass())) {
            throw new RuntimeException(String.format("ArgParse: cannot fill object of type %s (expected %s)", argObject.getClass(), formatDefinition));
        }

        // prepare plainArgs collection
        Collection<String> plainArgs;
        if (plainArgsField != null) {
            try {
                @SuppressWarnings("unchecked")
                Collection<String> uncheckedCastTmp = (Collection<String>) plainArgsField.get(argObject);
                plainArgs = uncheckedCastTmp;
                if (plainArgs == null) {
                    throw new RuntimeException(String.format("ArgParse: collection '%s' is not initialized.", plainArgsField.getName()));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(String.format("ArgParse: @PlainArgs field '%s' is inaccessible", plainArgsField.getName()));
            }
        } else {
            plainArgs = new HashSet<>();
        }

        // keep track of arguments that we encountered
        Set<ArgumentDefinition> appliedArguments = new HashSet<>();

        // iterate through strings from command line
        for (int idx = offset; idx < args.length; idx++) {
            String argument = args[idx];
            if (argument.startsWith("---")) {
                /**** too-long argument ****/
                throw new ArgParseException("Leading string '---' is not allowed", null, null);

            } else if ("--".equals(argument)) {
                /**** plain argument separator string ****/
                // eat all remaining arguments, starting with the next one
                for (idx += 1; idx < args.length; idx++) plainArgs.add(args[idx]);
                break;

            } else if (argument.startsWith("--")) {
                /**** long argument ****/
                String longArgument = argument.substring(2);
                String longName = longArgument;
                String longValue = null;
                int eq = longArgument.indexOf('=');
                if (eq > -1) {
                    longName = longArgument.substring(0, eq);
                    longValue = longArgument.substring(eq + 1);
                }

                ArgumentDefinition definition = longNames.get(longName);
                if (definition == null) {
                    throw new UnrecognizedArgumentException(longName);
                }
                definition.applyArgument(argObject, longName, longValue);
                appliedArguments.add(definition);

            } else if (argument.startsWith("-")) {
                /**** short argument(s) ****/
                /* TODO -abcdef */
                String shortName = argument.substring(1);
                String shortValue = null;
                ArgumentDefinition definition = shortNames.get(shortName);
                if (definition == null) {
                    throw new UnrecognizedArgumentException(shortName);
                }
                if (definition.needsValue()) {
                    /* advance to next argument */
                    idx++;
                    if (idx >= args.length) {
                        /* We have run out of arguments. */
                        throw new MissingValueException("Argument requires value but no more arguments supplied", shortName);
                    }
                    shortValue = args[idx];
                }
                definition.applyArgument(argObject, shortName, shortValue);
                appliedArguments.add(definition);

            } else if (subcommands.containsKey(argument)) {
                Field[] fields = argObject.getClass().getFields();
                
                try {
					Field field = argObject.getClass().getField(argument);
					
					ArgParse subcommand = subcommands.get(argument);
					
					Object subcommandObject = field.getType().newInstance();
					
					field.set(argObject, subcommandObject);			                
					
					subcommand.parseArgs(args, subcommandObject, idx + 1);
					
				} catch (Exception e) {
					e.printStackTrace();
				} 
                
                break;            	
            } else {
                /**** plain argument ****/
                plainArgs.add(argument);
            }
        }

        // check if all mandatory arguments have been specified
        for (ArgumentDefinition arg : arguments) {
            if (arg.isMandatory() && !appliedArguments.contains(arg)) {
                throw new MandatoryNotPresentException(arg.toString());
            }
        }
    }

    /** Parse provided argument strings, and fill out respective fields in the supplied <tt>argObject</tt>.
     *
     * <p>The <tt>argObject</tt> should have proper annotations to describe fields.
     *
     * @param args command line arguments to parse
     * @param argObject properly annotated object
     * @param offset first argument to parse
     * @throws ArgParseException when an error is found in the supplied arguments
     * @throws RuntimeException when an error is found in <tt>argObject</tt> formatDefinition
     */
    public static void parse(String[] args, Object argObject, int offset)
    throws ArgParseException, RuntimeException {
        ArgParse ap = new ArgParse(argObject.getClass());
        ap.parseArgs(args, argObject, offset);
    }


    /** Parse provided argument strings, and fill out respective fields in the supplied <tt>argObject</tt>.
     *
     * <p>The <tt>argObject</tt> should have proper annotations to describe fields.
     *
     * <p>This is a shorthand function to call {@link ArgParse#parse(String[], Object, int)} with <tt>offset</tt> 1,
     * in the common case when supplying <tt>args</tt> directly from <tt>public static void main</tt> method. This
     * skips first argument, which is the name of the program.
     *
     * @param args command line arguments to parse
     * @param argObject properly annotated object
     * @throws ArgParseException when an error is found in the supplied arguments
     * @throws RuntimeException when an error is found in <tt>argObject</tt> formatDefinition
     */
    public static void parse(String[] args, Object argObject)
        throws ArgParseException, RuntimeException {
        parse(args, argObject, 1);
    }

    /** Return help text as string
     * @param argObject properly annotated object from which the help is derived
     * @return help text derived from <tt>argObject</tt> annotations
     */
    public static String help(Object argObject)
    {
        ArgParse ap = new ArgParse(argObject.getClass());
        return ap.getHelp();
    }
}
