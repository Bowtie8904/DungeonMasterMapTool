// Per-class creation guidance from the Daggerheart SRD (Darrington Press Community Gaming License):
// suggested traits (Agility, Strength, Finesse, Instinct, Presence, Knowledge), starting gear, class items and question prompts.
// Assassin, Brawler, Warlock and Witch have user-provided suggested traits and gear.
// Classes that are not listed here fall back to GENERIC_GUIDE entirely.

export interface ClassGuide {
  traits: number[];
  primary: string;
  secondary?: string;
  armor: string;
  classItems: string[];
  background: string[];
  connections: string[];
}

/** Guidance with optional suggestions for classes without known recommendations. */
export type ClassGuideSource = Omit<ClassGuide, 'traits' | 'primary' | 'armor'> & Partial<Pick<ClassGuide, 'traits' | 'primary' | 'armor'>>;

export const CLASS_GUIDES: Record<string, ClassGuideSource> = {
  "Assassin": {
    "traits": [2, -1, 1, 0, 0, 1],
    "primary": "Katana",
    "armor": "Brigandine Armor",
    "classItems": [
      "A list of names with several marked off",
      "a rusted blade inscribed with an insignia"
    ],
    "background": [
      "You once killed someone you were close to. What happened, and how did it change you?",
      "What organization trained you in the art of killing, and how did you become a member?",
      "Throughout your career, one target has eluded you. Who are they, and how have they slipped through your fingers?"
    ],
    "connections": [
      "I've killed someone for you. Who were they?",
      "How did you save me when I was on the brink of death? What have I promised you as repayment?",
      "What secret about myself did I tell you, and how did it change your view of me?"
    ]
  },
  "Bard": {
    "traits": [
      0,
      -1,
      1,
      0,
      2,
      1
    ],
    "primary": "Rapier",
    "armor": "Gambeson Armor",
    "classItems": [
      "A romance novel",
      "a letter never opened"
    ],
    "background": [
      "Who from your community taught you to have such confidence in yourself?",
      "You were in love once. Who did you adore, and how did they hurt you?",
      "You've always looked up to another bard. Who are they, and why do you idolize them?"
    ],
    "connections": [
      "What made you realize we were going to be such good friends?",
      "What do I do that annoys you?",
      "Why do you grab my hand at night?"
    ]
  },
  "Brawler": {
    "traits": [1, 1, 0, 2, 0, -1],
    "primary": "Brawler's Strike",
    "armor": "Brigandine Armor",
    "classItems": [
      "hand wraps from a mentor",
      "a book about your secret hobby"
    ],
    "background": [
      "Where did you spend time during your formative years that taught you, directly or indirectly, how to fight in the style you use?",
      "What organization has vowed to kill you on sight, and what did you do to invoke their ire?",
      "Who did you recently lose a fight to that you're desperate for a rematch against?"
    ],
    "connections": [
      "What is one thing we're both afraid of?",
      "What do I rely on you for during our travels? How do you feel about it?",
      "I still haven't forgiven you for something you said to me. What was it, and why did you say it?"
    ]
  },
  "Druid": {
    "traits": [
      1,
      0,
      1,
      2,
      -1,
      0
    ],
    "primary": "Shortstaff",
    "armor": "Leather Armor",
    "classItems": [
      "A small bag of rocks and bones",
      "a strange pendant found in the dirt"
    ],
    "background": [
      "Why was the community you grew up in so reliant on nature and its creatures?",
      "Who was the first wild animal you bonded with? Why did your bond end?",
      "Who has been trying to hunt you down? What do they want from you?"
    ],
    "connections": [
      "What did you confide in me that makes me leap into danger for you every time?",
      "What animal do I say you remind me of?",
      "What affectionate nickname have you given me?"
    ]
  },
  "Guardian": {
    "traits": [
      1,
      2,
      -1,
      0,
      1,
      0
    ],
    "primary": "Battleaxe",
    "armor": "Chainmail Armor",
    "classItems": [
      "A totem from your mentor",
      "a secret key"
    ],
    "background": [
      "Who from your community did you fail to protect, and why do you still think of them?",
      "You've been tasked with protecting something important and delivering it somewhere dangerous. What is it, and where does it need to go?",
      "You consider an aspect of yourself to be a weakness. What is it, and how has it affected you?"
    ],
    "connections": [
      "How did I save your life the first time we met?",
      "What small gift did you give me that you notice I always carry with me?",
      "What lie have you told me about yourself that I absolutely believe?"
    ]
  },
  "Ranger": {
    "traits": [
      2,
      0,
      1,
      1,
      -1,
      0
    ],
    "primary": "Shortbow",
    "armor": "Leather Armor",
    "classItems": [
      "A trophy from your first kill",
      "a seemingly broken compass"
    ],
    "background": [
      "A terrible creature hurt your community, and you've vowed to hunt them down. What are they, and what unique trail or sign do they leave behind?",
      "Your first kill almost killed you, too. What was it, and what part of you was never the same after that event?",
      "You've traveled many dangerous lands, but what is the one place you refuse to go?"
    ],
    "connections": [
      "What friendly competition do we have?",
      "Why do you act differently when we're alone than when others are around?",
      "What threat have you asked me to watch for, and why are you worried about it?"
    ]
  },
  "Rogue": {
    "traits": [
      1,
      -1,
      2,
      0,
      1,
      0
    ],
    "primary": "Dagger",
    "armor": "Gambeson Armor",
    "classItems": [
      "A set of forgery tools",
      "a grappling hook"
    ],
    "background": [
      "What did you get caught doing that got you exiled from your home community?",
      "You used to have a different life, but you've tried to leave it behind. Who from your past is still chasing you?",
      "Who from your past were you most sad to say goodbye to?"
    ],
    "connections": [
      "What did I recently convince you to do that got us both in trouble?",
      "What have I discovered about your past that I hold secret from the others?",
      "Who do you know from my past, and how have they influenced your feelings about me?"
    ]
  },
  "Seraph": {
    "traits": [
      0,
      2,
      0,
      1,
      1,
      -1
    ],
    "primary": "Hallowed Axe",
    "armor": "Chainmail Armor",
    "classItems": [
      "A bundle of offerings",
      "a sigil of your god"
    ],
    "background": [
      "Which god did you devote yourself to? What incredible feat did they perform for you in a moment of desperation?",
      "How did your appearance change after taking your oath?",
      "In what strange or unique way do you communicate with your god?"
    ],
    "connections": [
      "What promise did you make me agree to, should you die on the battlefield?",
      "Why do you ask me so many questions about my god?",
      "You've told me to protect one member of our party above all others, even yourself. Who are they and why?"
    ]
  },
  "Sorcerer": {
    "traits": [
      0,
      -1,
      1,
      2,
      1,
      0
    ],
    "primary": "Dualstaff",
    "armor": "Gambeson Armor",
    "classItems": [
      "A whispering orb",
      "a family heirloom"
    ],
    "background": [
      "What did you do that made the people in your community wary of you?",
      "What mentor taught you to control your untamed magic, and why are they no longer able to guide you?",
      "You have a deep fear you hide from everyone. What is it, and why does it scare you?"
    ],
    "connections": [
      "Why do you trust me so deeply?",
      "What did I do that makes you cautious around me?",
      "Why do we keep our shared past a secret?"
    ]
  },
  "Warlock": {
    "traits": [1, -1, 0, 1, 2, 0],
    "primary": "Scimitar",
    "secondary": "Fighting Cloak",
    "armor": "Mage Robes",
    "classItems": [
      "A carving that symbolizes your patron",
      "a ring you can't remove"
    ],
    "background": [
      "Who from your community shunned you after you made a pact with your patron?",
      "What desperate situation led you to pledge your life to your patron?",
      "Your patron has given you one task you must accomplish above all else. What is it, and why does it worry you?"
    ],
    "connections": [
      "Why do you think I confide in you about what my patron says and does?",
      "Our relationship has changed since you saw me show tribute to my patron. What did you see, and how has it affected you?",
      "I once did something very foolish, and you've never let me live it down. What was it?"
    ]
  },
  "Warrior": {
    "traits": [
      2,
      1,
      0,
      1,
      -1,
      0
    ],
    "primary": "Longsword",
    "armor": "Chainmail Armor",
    "classItems": [
      "The drawing of a lover",
      "a sharpening stone"
    ],
    "background": [
      "Who taught you to fight, and why did they stay behind when you left home?",
      "Somebody defeated you in battle years ago and left you to die. Who was it, and how did they betray you?",
      "What legendary place have you always wanted to visit, and why is it so special?"
    ],
    "connections": [
      "We knew each other long before this party came together. How?",
      "What mundane task do you usually help me with off the battlefield?",
      "What fear am I helping you overcome?"
    ]
  },
  "Witch": {
    "traits": [0, -1, 0, 2, 1, 1],
    "primary": "Casting Dagger",
    "secondary": "Rune Shield",
    "armor": "Mage Robes",
    "classItems": [
      "A small, harmless pet",
      "a scrying stone"
    ],
    "background": [
      "Who from your community feared your magical craft? What rumor did they spread about you, and what truth did it contain?",
      "You once used your power to help someone in a dire situation. Who were they, and why did they come to you?",
      "Your magic once opened a door best left closed. Who or what was on the other side?"
    ],
    "connections": [
      "What unique ritual or practice have I taught you that we now perform together?",
      "I once appeared to you in a dream and shared a vision of the future. What did I tell you?",
      "What do you typically come to me for advice about?"
    ]
  },
  "Wizard": {
    "traits": [
      -1,
      0,
      0,
      1,
      1,
      2
    ],
    "primary": "Greatstaff",
    "armor": "Leather Armor",
    "classItems": [
      "A book you’re trying to translate",
      "a tiny, harmless elemental pet"
    ],
    "background": [
      "What responsibilities did your community once count on you for? How did you let them down?",
      "You've spent your life searching for a book or object of great significance. What is it, and why is it so important to you?",
      "You have a powerful rival. Who are they, and why are you so determined to defeat them?"
    ],
    "connections": [
      "What favor have I asked of you that you're not sure you can fulfill?",
      "What weird hobby or strange fascination do we both share?",
      "What secret about yourself have you entrusted only to me?"
    ]
  }
};

export const GENERIC_GUIDE: ClassGuide = {
  traits: [0, 1, 1, 0, 2, -1],
  primary: '',
  armor: '',
  classItems: ['A keepsake from your past'],
  background: [
    'What drove you to become an adventurer?',
    'Who or what do you fight for?',
    'What is the one thing you refuse to do, no matter the cost?',
  ],
  connections: [
    'What do I know about you that nobody else does?',
    'What mistake of yours did I help you recover from?',
    'Why do I trust you, even when I should not?',
  ],
};
