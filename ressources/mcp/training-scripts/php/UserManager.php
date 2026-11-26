<?php

class UserManager {
    private $db;

    public function __construct($db) {
        $this->db = $db;
    }

    public function formatEmail($email) {
        if (!$email) {
            return null;
        }
        $clean = trim($email);
        $lower = strtolower($clean);
        if (!filter_var($lower, FILTER_VALIDATE_EMAIL)) {
            throw new Exception("Email invalide");
        }
        return $lower;
    }
}