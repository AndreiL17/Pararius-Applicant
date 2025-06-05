package api.pararius_applier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ParariusApplierApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParariusApplierApplication.class, args);
    }

}
