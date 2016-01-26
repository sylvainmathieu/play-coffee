package play.modules.coffee;

public class CoffeeScriptException extends RuntimeException {

	public String description;

	public CoffeeScriptException(String message, String description) {
		super(message);
		this.description = description;
	}

}