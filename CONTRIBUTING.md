# Contributing

## Setup

It's fairly simple to begin contributing Javadocs.

### Prerequisites

- You have an account on [GitHub](https://github.com), and know how to use basic GitHub functions such as Pull Requests.
- You have basic knowledge of [git](https://docs.github.com/en/get-started/using-git/about-git) (such as what commits are, what branches are), and using it (cloning a repository, pushing, pulling). This guide assumes you are using the terminal/command line for git operations. If you are using a GUI for git, please consult that application's manual/user guide on how to do these operations.
- You have access to a Chromium-based web browser. Annotater's editor makes use of the [Filesystem API](https://caniuse.com/filesystem), which is currently unsupported in Safari and Firefox based browsers.

Before contributing documentation, please be sure to read our [naming conventions](./CONVENTIONS.md).

### Steps

1. **Fork the repository.** Forking the repository creates a copy of the repository (your _fork_) at your personal account, where you push your own commits to your branches.

1. **Clone your forked repository locally.** The repository directory can have any name, by default cloning will make a directory with the same name as the repository.

1. **Open mcsrc.dev and select the Settings gear icon in the top left, select Tools in the left sidebar, then click Open Annotater directory.** [mcsrc.dev](https://mcsrc.dev/) allows you to view the Minecraft source and add Javadocs. This will give the site permission to write to your local Annotater repository, making it far easier to contribute documentation

1. **Write Javadocs.**
    - Classes are listed at the left section of the interface, and a search bar is available in the top left. Click on a class to open it.
    - To add Javadocs to a class, field, or method, click `Edit Javadoc` above the class, field, or method in question.
    - Changes are saved automatically.

1. **Commit and push your changes.** Once you're done making changes, save your work by committing it and pushing it to GitHub.

1. **Open a Pull Request.** Pull Requests are reviewed and merged by our triage team, currently being assembled.
