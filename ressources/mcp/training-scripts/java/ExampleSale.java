import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ExempleSale {
    private String role;
    private String path;

    public ExempleSale(String role, String path) {
        this.role = role;
        this.path = path;
    }

    public String getPermissions() {
        if (role == null) {
            return "NONE";
        } else if (role.equals("ADMIN")) {
            return "ALL";
        } else if (role.equals("USER")) {
            return "READ";
        } else {
            return "GUEST";
        }
    }

    public String readFirstLine() throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine();
        br.close();
        return line;
    }
}