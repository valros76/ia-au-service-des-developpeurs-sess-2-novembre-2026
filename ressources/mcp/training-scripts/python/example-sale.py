import os

class UserService:
    def __init__(self, data_dir):
        self.data_dir = data_dir

    def read_user_email(self, username):
        path = os.path.join(self.data_dir, username + ".txt")
        try:
            f = open(path, "r")
            content = f.read().strip()
            f.close()
            if "@" not in content:
                return None
            return content.lower()
        except:
            return None